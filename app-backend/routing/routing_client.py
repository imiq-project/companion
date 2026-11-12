"""Client for the configured ranked-route provider."""
from __future__ import annotations
from routing.validation import RoutingBridgeError
import json
import math
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from infrastructure.service_config import positive_number, provider_url
from infrastructure.http_client import read_json_response



class RoutingUnavailableError(RuntimeError):
    pass


class RoutingClient:
    def __init__(self, base_url=None, timeout=None):
        self.base_url = provider_url('COMPANION_ROUTING_BASE_URL','http://127.0.0.1:8000',base_url)
        parsed = urlsplit(self.base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise RoutingBridgeError("invalid server-configured routing URL")
        self.timeout = positive_number('COMPANION_ROUTING_TIMEOUT_SECONDS',120,timeout)
        if not math.isfinite(self.timeout) or self.timeout <= 0:
            raise RoutingBridgeError("routing timeout must be positive and finite")

    def ranked_routes(self, payload):
        request = Request(self.base_url + "/ranked-routes", data=json.dumps(payload, allow_nan=False).encode(), headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(request, timeout=self.timeout) as response:
                data = read_json_response(response, 16 * 1024 * 1024)
        except HTTPError as exc:
            raise RoutingUnavailableError(f"routing returned HTTP {exc.code}") from exc
        except (URLError, TimeoutError, OSError, ValueError) as exc:
            raise RoutingUnavailableError("routing unavailable or returned invalid JSON") from exc
        if not isinstance(data, dict) or not isinstance(data.get("routes"), list):
            raise RoutingUnavailableError("routing response must contain a routes array")
        return data
