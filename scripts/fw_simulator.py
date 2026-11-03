"""Firmware Upgrade Device Simulator.

This script implements the device fleet simulator described in ``device_simulator.txt``.
It is packaged as a Python module ``scripts.fw_simulator`` so that the Docker
image can start it with ``uvicorn scripts.fw_simulator:app``.
"""

import asyncio
import os
import random
import time
import uuid
from collections import Counter
from contextlib import asynccontextmanager
from typing import Any, Coroutine, Dict

import httpx
from fastapi import FastAPI, HTTPException

# ---------------------------------------------------------------------------
# Types
# ---------------------------------------------------------------------------
JsonObject = Dict[str, Any]
Device = Dict[str, Any]

# ---------------------------------------------------------------------------
# Configuration (environment variables)
# ---------------------------------------------------------------------------
CONTROLLER_URL = os.getenv("CONTROLLER_URL", "http://localhost:8000").rstrip("/")
DEVICE_COUNT = int(os.getenv("DEVICE_COUNT", "1000"))
SEED = int(os.getenv("SEED", "42"))
OLD_VERSION = os.getenv("OLD_VERSION", "1.0.0")
PERIODIC_INFORM_SEC = int(os.getenv("PERIODIC_INFORM_SEC", "300"))
DOWNLOAD_MIN_SEC = int(os.getenv("DOWNLOAD_MIN_SEC", "2"))
DOWNLOAD_MAX_SEC = int(os.getenv("DOWNLOAD_MAX_SEC", "20"))
REBOOT_MIN_SEC = int(os.getenv("REBOOT_MIN_SEC", "5"))
REBOOT_MAX_SEC = int(os.getenv("REBOOT_MAX_SEC", "240"))
P_DUPLICATE = float(os.getenv("P_DUPLICATE", "0.05"))
P_LATE = float(os.getenv("P_LATE", "0.05"))
LATE_EXTRA_SEC = int(os.getenv("LATE_EXTRA_SEC", "600"))

PROFILES = {
    "offline": float(os.getenv("P_OFFLINE", "0.05")),
    "reject": float(os.getenv("P_REJECT", "0.03")),
    "fault": float(os.getenv("P_FAULT", "0.04")),
    "no_tc": float(os.getenv("P_NO_TC", "0.03")),
    "revert": float(os.getenv("P_REVERT", "0.02")),
}

# ---------------------------------------------------------------------------
# Validation of configuration values
# ---------------------------------------------------------------------------
def validate_settings() -> None:
    probabilities = {"P_DUPLICATE": P_DUPLICATE, "P_LATE": P_LATE, **PROFILES}
    invalid = [name for name, value in probabilities.items() if not 0 <= value <= 1]
    if invalid:
        raise ValueError(f"Probabilities must be between 0 and 1: {', '.join(invalid)}")
    if sum(PROFILES.values()) > 1:
        raise ValueError("The sum of profile probabilities must not exceed 1")
    if DEVICE_COUNT < 1:
        raise ValueError("DEVICE_COUNT must be greater than zero")
    if PERIODIC_INFORM_SEC < 1:
        raise ValueError("PERIODIC_INFORM_SEC must be greater than zero")
    if DOWNLOAD_MIN_SEC < 0 or DOWNLOAD_MAX_SEC < DOWNLOAD_MIN_SEC:
        raise ValueError("Download delay bounds must be non‑negative and ordered")
    if REBOOT_MIN_SEC < 0 or REBOOT_MAX_SEC < REBOOT_MIN_SEC:
        raise ValueError("Reboot delay bounds must be non‑negative and ordered")
    if LATE_EXTRA_SEC < 60:
        raise ValueError("LATE_EXTRA_SEC must be at least 60")

validate_settings()

# ---------------------------------------------------------------------------
# Global state
# ---------------------------------------------------------------------------
fleet_rng = random.Random(SEED)
rng = random.Random(SEED + 1)
devices: dict[str, Device] = {}
client: httpx.AsyncClient | None = None
background_tasks: set[asyncio.Task[None]] = set()

# ---------------------------------------------------------------------------
# Helper utilities for fire‑and‑forget tasks
# ---------------------------------------------------------------------------
def _task_finished(task: asyncio.Task[None]) -> None:
    background_tasks.discard(task)
    if task.cancelled():
        return
    exc = task.exception()
    if exc is not None:
        print(f"[sim] Background task failed: {exc!r}")

def spawn(coro: Coroutine[Any, Any, None]) -> None:
    """Schedule a coroutine as a fire‑and‑forget task and keep a reference.
    """
    task = asyncio.create_task(coro)
    background_tasks.add(task)
    task.add_done_callback(_task_finished)

# ---------------------------------------------------------------------------
# Profile selection
# ---------------------------------------------------------------------------
def pick_profile() -> str:
    threshold = fleet_rng.random()
    cumulative = 0.0
    for name, share in PROFILES.items():
        cumulative += share
        if threshold < cumulative:
            return name
    return "healthy"

# ---------------------------------------------------------------------------
# HTTP helpers – POST to controller with optional duplicate
# ---------------------------------------------------------------------------
async def post(path: str, body: JsonObject) -> JsonObject | None:
    if client is None:
        raise RuntimeError("HTTP client not initialized")
    try:
        response = await client.post(f"{CONTROLLER_URL}{path}", json=body)
        response.raise_for_status()
        # Random duplicate according to P_DUPLICATE
        if rng.random() < P_DUPLICATE:
            spawn(duplicate_post(path, body))
        payload = response.json() if response.content else {}
        if not isinstance(payload, dict):
            raise ValueError("Controller response must be a JSON object")
        return payload
    except (httpx.HTTPError, ValueError) as exc:
        print(f"[sim] POST {path} failed: {exc!r}")
        return None

async def duplicate_post(path: str, body: JsonObject) -> None:
    await asyncio.sleep(rng.uniform(0.1, 5))
    if client is None:
        return
    try:
        resp = await client.post(f"{CONTROLLER_URL}{path}", json=body)
        resp.raise_for_status()
    except httpx.HTTPError as exc:
        print(f"[sim] Duplicate POST {path} failed: {exc!r}")

# ---------------------------------------------------------------------------
# Core simulator logic
# ---------------------------------------------------------------------------
async def inform(device: Device, event: str, transfer_complete: JsonObject | None = None) -> None:
    body: JsonObject = {
        "deviceId": device["id"],
        "firmwareVersion": device["version"],
        "event": event,
    }
    if transfer_complete is not None:
        body["transferComplete"] = transfer_complete
    response = await post(f"/devices/{device['id']}/inform", body)
    for command in (response or {}).get("commands", []):
        if command.get("type") == "DOWNLOAD":
            spawn(handle_download(device, command))

async def respond(device: Device, command_key: str, status: str, reason: str | None = None) -> None:
    body: JsonObject = {"commandKey": command_key, "status": status}
    if reason is not None:
        body["reason"] = reason
    await post(f"/devices/{device['id']}/command-response", body)

async def handle_download(device: Device, command: JsonObject) -> None:
    command_key = command.get("commandKey")
    target_version = command.get("targetVersion")
    if not command_key or not target_version:
        await respond(device, command_key or "", "REJECTED", "MALFORMED_COMMAND")
        return
    if device["busy"]:
        await respond(device, command_key, "REJECTED", "BUSY")
        return
    if device["profile"] == "reject":
        await respond(device, command_key, "REJECTED", "UNSUPPORTED_IMAGE")
        return

    device["busy"] = True
    try:
        await respond(device, command_key, "ACCEPTED")
        start_time = time.time()
        await asyncio.sleep(rng.uniform(DOWNLOAD_MIN_SEC, DOWNLOAD_MAX_SEC))
        await asyncio.sleep(rng.uniform(REBOOT_MIN_SEC, REBOOT_MAX_SEC))
        complete_time = time.time()

        profile = device["profile"]
        if profile in ("healthy", "no_tc"):
            device["version"] = target_version

        if profile in ("no_tc", "revert"):
            await inform(device, "BOOT")
            return

        transfer_complete = {
            "commandKey": command_key,
            "faultCode": 9010 if profile == "fault" else 0,
            "startTime": start_time,
            "completeTime": complete_time,
        }
        if rng.random() < P_LATE:
            await inform(device, "BOOT")
            await asyncio.sleep(rng.uniform(60, LATE_EXTRA_SEC))
        await inform(device, "TRANSFER_COMPLETE", transfer_complete)
    finally:
        device["busy"] = False

async def periodic_loop(device: Device) -> None:
    await asyncio.sleep(rng.uniform(0, PERIODIC_INFORM_SEC))
    while True:
        if device["profile"] != "offline" and not device["busy"]:
            await inform(device, "PERIODIC")
        await asyncio.sleep(PERIODIC_INFORM_SEC)

# ---------------------------------------------------------------------------
# Application lifespan – initialise HTTP client and device fleet
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(_app: FastAPI):
    global client
    client = httpx.AsyncClient(limits=httpx.Limits(max_connections=500), timeout=10)
    devices.clear()
    for _ in range(DEVICE_COUNT):
        device_id = f"GW-{uuid.UUID(int=fleet_rng.getrandbits(128)).hex[:12].upper()}"
        devices[device_id] = {
            "id": device_id,
            "version": OLD_VERSION,
            "profile": pick_profile(),
            "busy": False,
        }
    # start periodic tasks for each device
    periodic_tasks = [asyncio.create_task(periodic_loop(d)) for d in devices.values()]
    profile_counts = dict(Counter(d["profile"] for d in devices.values()))
    print(f"[sim] {DEVICE_COUNT} devices -> {CONTROLLER_URL}: {profile_counts}")
    try:
        yield
    finally:
        for t in periodic_tasks + list(background_tasks):
            t.cancel()
        await asyncio.gather(*periodic_tasks, *background_tasks, return_exceptions=True)
        background_tasks.clear()
        await client.aclose()
        client = None

# ---------------------------------------------------------------------------
# FastAPI app and endpoints
# ---------------------------------------------------------------------------
app = FastAPI(title="Firmware Upgrade Device Simulator", lifespan=lifespan)

@app.get("/health")
async def health() -> JsonObject:
    return {"status": "ok"}

@app.post("/devices/{device_id}/connection-request", status_code=202)
async def connection_request(device_id: str) -> JsonObject:
    device = devices.get(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="Unknown device")
    if device["profile"] != "offline" and not device["busy"]:
        async def wake_device() -> None:
            await asyncio.sleep(rng.uniform(0.2, 3))
            await inform(device, "CONNECTION_REQUEST")
        spawn(wake_device())
    return {"delivered": True}

@app.get("/devices")
async def list_devices(version: str | None = None) -> list[JsonObject]:
    """List devices without revealing internal behavior profiles."""
    return [
        {"deviceId": d["id"], "firmwareVersion": d["version"]}
        for d in devices.values()
        if version is None or d["version"] == version
    ]

@app.get("/_admin/summary")
async def admin_summary() -> JsonObject:
    """Return interviewer‑only ground truth for grading the run report."""
    return {
        "profiles": dict(Counter(d["profile"] for d in devices.values())),
        "versions": dict(Counter(d["version"] for d in devices.values())),
        "busy": sum(d["busy"] for d in devices.values()),
    }

