"""Loopback HTTP transport for execution and Java control ports."""

import asyncio
import hmac
import ipaddress
import json
from hashlib import sha256
from typing import Any, Callable
from urllib.parse import quote, urlsplit
from uuid import uuid4

import httpx
from aiohttp import web
from auralis_intelligence.contracts import ContractError, envelope, validate_message

from .ports import CallDenied, ControlPort, ControlUnavailable
from .runtime import RoundSupervisor

CONTROL_ROUTES = {
    "seed-evidence": ("GET", "/executions/{execution_id}/seed-evidence", None, "SeedEvidenceState"),
    "seed-evidence-save": (
        "PUT",
        "/executions/{execution_id}/seed-evidence",
        "SeedEvidenceUpdate",
        "SeedEvidenceState",
    ),
    "preference-resolution": (
        "POST",
        "/executions/{execution_id}/preference-resolution",
        "PreferenceResolutionProposal",
        "EffectivePreferenceSnapshot",
    ),
    "candidate-validation": (
        "POST",
        "/executions/{execution_id}/candidate-validation",
        "RecallProposal",
        "CandidateBundle",
    ),
    "invocation-grants": (
        "POST",
        "/executions/{execution_id}/invocation-grants",
        "InvocationGrants",
        "GrantResults",
    ),
    "usage-receipt": (
        "PUT",
        "/invocations/{invocation_id}/usage-receipts/{receipt_id}",
        "UsageReceipt",
        "UsageSettlement",
    ),
    "read-authorization": (
        "POST",
        "/executions/{execution_id}/read-authorization",
        "ReadAuthorization",
        "ReadAuthorizationResult",
    ),
    "read-receipts": (
        "POST",
        "/executions/{execution_id}/read-receipts",
        "ReadReceipts",
        "ReadRegistrationResult",
    ),
    "budget-state": ("GET", "/executions/{execution_id}/budget-state", None, "BudgetState"),
    "delivery": (
        "PUT",
        "/executions/{execution_id}/deliveries/{delivery_id}",
        "Delivery",
        "DeliveryResult",
    ),
}


class ControlHTTPClient:
    def __init__(self, base_url: str, token: str, *, timeout_seconds: float = 15) -> None:
        parts = urlsplit(base_url)
        try:
            loopback = ipaddress.ip_address(parts.hostname or "").is_loopback
        except ValueError:
            loopback = False
        if (
            not loopback
            or parts.scheme != "http"
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
            or not token
        ):
            raise ValueError("LOOPBACK_CONTROL_CONFIGURATION_REQUIRED")
        self.client = httpx.AsyncClient(
            base_url=base_url.rstrip("/") + "/",
            headers={"Authorization": "Bearer " + token, "X-Auralis-Audience": "coordinator"},
            transport=httpx.AsyncHTTPTransport(retries=0),
            trust_env=False,
            follow_redirects=False,
            timeout=timeout_seconds,
        )

    async def request(
        self, execution_id: str, operation: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        method, path, request_type, result_type = CONTROL_ROUTES[operation]
        identifiers = {
            "execution_id": quote(execution_id, safe=""),
            **{
                key: quote(str(payload[key]), safe="")
                for key in ("invocation_id", "receipt_id", "delivery_id")
                if key in payload
            },
        }
        path = "internal/v1" + path.format(**identifiers)
        request_id = uuid4().hex
        message = envelope(request_type, payload, request_id) if request_type else None
        try:
            response = await self.client.request(
                method, path, json=message if request_type else None
            )
        except httpx.HTTPError as error:
            raise ControlUnavailable("CONTROL_UNAVAILABLE") from error
        try:
            decoded = response.json()
            validate_message(decoded)
        except (ValueError, TypeError, KeyError) as error:
            raise ControlUnavailable("CONTROL_INVALID_RESPONSE") from error
        if response.status_code >= 500:
            raise ControlUnavailable("CONTROL_UNAVAILABLE")
        if request_type and decoded["request_id"] != request_id:
            raise ControlUnavailable("CONTROL_RESPONSE_MISMATCH")
        if decoded["message_type"] == "Error":
            raise ContractError(decoded["payload"]["code"])
        if response.status_code >= 400 or decoded["message_type"] != result_type:
            raise ControlUnavailable("CONTROL_INVALID_RESPONSE")
        return decoded["payload"]

    async def close(self) -> None:
        await self.client.aclose()


def _error(code: str, request_id: str):
    mapping = {
        "EXECUTOR_BUSY": (409, "WAIT_FOR_SLOT"),
        "IDEMPOTENCY_CONFLICT": (409, "NEW_EXPLICIT_INTENT"),
        "EXECUTION_GONE": (410, "STOP_OLD_EXECUTION"),
        "STALE_BOOT": (409, "STOP_OLD_EXECUTION"),
        "MAINTENANCE": (503, "WAIT_FOR_MAINTENANCE"),
        "UNAUTHENTICATED": (401, "STOP"),
        "FORBIDDEN": (403, "STOP"),
        "BINDING_MISMATCH": (409, "STOP_OLD_EXECUTION"),
        "SUBJECT_REVOKED": (410, "CANCEL_AND_PURGE"),
        "DEADLINE_EXCEEDED": (410, "FINALIZE"),
        "DEPENDENCY_UNAVAILABLE": (503, "READ_STATUS_OR_SAFE_RETRY"),
    }
    status, retry = mapping.get(code, (400, "FIX_INPUT"))
    safe_code = (
        code
        if code in mapping
        or code in {"INVALID_PAYLOAD", "POLICY_OUT_OF_RANGE", "UNKNOWN_CAPABILITY"}
        else "INVALID_PAYLOAD"
    )
    return web.json_response(
        envelope(
            "Error",
            {"code": safe_code, "retry_disposition": retry, "reason": safe_code.lower()},
            request_id,
        ),
        status=status,
        headers={"Cache-Control": "no-store"},
    )


def service_middleware(token: str, audience: str):
    @web.middleware
    async def boundary(request, handler):
        request["request_id"] = uuid4().hex
        supplied = request.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer " + token).encode("utf-8")):
            return _error("UNAUTHENTICATED", request["request_id"])
        if request.headers.get("X-Auralis-Audience") != audience:
            return _error("FORBIDDEN", request["request_id"])
        try:
            return await handler(request)
        except (ContractError, CallDenied) as error:
            return _error(str(error), request["request_id"])
        except (ValueError, KeyError, TypeError, web.HTTPException):
            return _error("INVALID_PAYLOAD", request["request_id"])
        except ControlUnavailable:
            return _error("DEPENDENCY_UNAVAILABLE", request["request_id"])
        except Exception:
            return _error("DEPENDENCY_UNAVAILABLE", request["request_id"])

    return boundary


async def read_message(request: web.Request, expected: str) -> dict[str, Any]:
    message = json.loads((await request.read()).decode("utf-8"))
    validate_message(message)
    if message["message_type"] != expected:
        raise ContractError("INVALID_PAYLOAD")
    request["request_id"] = message["request_id"]
    return message["payload"]


def create_execution_app(
    supervisor: RoundSupervisor,
    control: ControlPort,
    factory: Callable,
    *,
    token: str,
    prepare: Callable | None = None,
) -> web.Application:
    if not token:
        raise ValueError("SERVICE_TOKEN_REQUIRED")
    app = web.Application(
        middlewares=[service_middleware(token, "intelligence")], client_max_size=4 * 1024 * 1024
    )
    erased = set()

    def response(kind, payload, request, status=200):
        return web.json_response(
            envelope(kind, payload, request["request_id"]),
            status=status,
            headers={"Cache-Control": "no-store"},
        )

    async def start(request):
        payload = await read_message(request, "RoundStart")
        if payload["execution_id"] != request.match_info["execution_id"]:
            raise ContractError("BINDING_MISMATCH")
        if payload["boot"]["python_boot_id"] != supervisor.boot_id:
            raise ContractError("STALE_BOOT")
        if sha256(payload["erasure_handle"].encode()).digest() in erased:
            raise ContractError("SUBJECT_REVOKED")
        if prepare:
            prepare(payload)
        status, execution = await supervisor.accept(payload, factory(payload), control)
        return response("ExecutionView", execution.view(), request, status)

    async def view(request):
        record = supervisor.records.get(request.match_info["execution_id"])
        if record is None:
            raise ContractError("EXECUTION_GONE")
        return response("ExecutionView", record[1].view(), request)

    async def command(request):
        payload = await read_message(request, "RoundCommand")
        if payload["command_id"] != request.match_info["command_id"]:
            raise ContractError("BINDING_MISMATCH")
        result = await supervisor.command(request.match_info["execution_id"], payload)
        return response("Acknowledgement", result, request, 202)

    async def admission(request):
        result = supervisor.admission(request.match_info["execution_id"], dict(request.query))
        return response("ExecutionAdmission", result, request)

    async def cancel(request):
        payload = await read_message(request, "Cancellation")
        result = await supervisor.cancel(
            request.match_info["execution_id"], payload["boot"], payload["reason"]
        )
        return response("ExecutionView", result, request)

    async def runtime(request):
        return response(
            "RuntimeView",
            {
                "boot_id": supervisor.boot_id,
                "status": "MAINTENANCE" if supervisor.maintenance else "READY",
                "execution_capacity": supervisor.execution_capacity,
                "active": [execution.view() for execution in supervisor.active.values()],
            },
            request,
        )

    async def erase(request):
        await read_message(request, "Erasure")
        handle = request.match_info["erasure_handle"]
        erased.add(sha256(handle.encode()).digest())
        for identifier, (_, execution, _) in list(supervisor.records.items()):
            if execution.start["erasure_handle"] == handle:
                await supervisor.cancel(identifier, execution.start["boot"], "SUBJECT_DELETED")
                supervisor.forget(identifier)
        return response("Acknowledgement", {"accepted": True}, request)

    async def shutdown(app):
        supervisor.maintenance = True
        await asyncio.gather(
            *(
                supervisor.cancel(execution.id, execution.start["boot"], "USER_CANCELLED")
                for execution in list(supervisor.active.values())
            )
        )

        await supervisor.resources.close()

    app.on_shutdown.append(shutdown)
    app.add_routes(
        [
            web.put("/internal/v1/executions/{execution_id}", start),
            web.get("/internal/v1/executions/{execution_id}", view),
            web.get("/internal/v1/executions/{execution_id}/admission", admission),
            web.put("/internal/v1/executions/{execution_id}/commands/{command_id}", command),
            web.put("/internal/v1/executions/{execution_id}/cancellation", cancel),
            web.get("/internal/v1/runtime", runtime),
            web.put("/internal/v1/erasures/{erasure_handle}", erase),
        ]
    )
    return app


def create_control_double_app(control, *, token: str) -> web.Application:
    """Test server for the actual HTTP client; not a Java product service."""
    app = web.Application(
        middlewares=[service_middleware(token, "coordinator")], client_max_size=4 * 1024 * 1024
    )
    for operation, (method, path, request_type, result_type) in CONTROL_ROUTES.items():

        async def handler(
            request, operation=operation, request_type=request_type, result_type=result_type
        ):
            payload = await read_message(request, request_type) if request_type else {}
            execution_id = request.match_info.get("execution_id")
            if operation == "usage-receipt":
                if any(
                    payload[key] != request.match_info[key]
                    for key in ("invocation_id", "receipt_id")
                ):
                    raise ContractError("BINDING_MISMATCH")
                invocation = control.invocations.get(payload["invocation_id"])
                if invocation is None:
                    raise ContractError("BINDING_MISMATCH")
                execution_id = invocation["execution_id"]
            if (
                operation == "delivery"
                and payload["delivery_id"] != request.match_info["delivery_id"]
            ):
                raise ContractError("BINDING_MISMATCH")
            result = await control.request(execution_id, operation, payload)
            return web.json_response(envelope(result_type, result, request["request_id"]))

        app.router.add_route(method, "/internal/v1" + path, handler)
    return app
