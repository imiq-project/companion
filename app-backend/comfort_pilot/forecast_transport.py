"""Bounded, lossless JSON transport for research forecasts, including old clients."""
from infrastructure.longitudinal_names import contract_view as _namespace_view
import gzip
import io
import json

WIRE_LIMIT = 32 * 1024 * 1024
EXPANDED_LIMIT = 32 * 1024 * 1024
LEGACY_PLAIN_LIMIT = 4 * 1024 * 1024


class ForecastBodyError(ValueError):
    def __init__(self, error, status):
        super().__init__(error)
        self.error, self.status = error, status


def read_forecast_json(request):
    encoding = request.headers.get('Content-Encoding', 'identity').lower().strip()
    if encoding not in ('identity', 'gzip'):
        raise ForecastBodyError('unsupported_forecast_content_encoding', 415)
    if request.content_length is not None and request.content_length > WIRE_LIMIT:
        raise ForecastBodyError('prospective_request_too_large', 413)
    body = request.stream.read(WIRE_LIMIT + 1)
    if len(body) > WIRE_LIMIT:
        raise ForecastBodyError('prospective_request_too_large', 413)
    try:
        if encoding == 'gzip':
            with gzip.GzipFile(fileobj=io.BytesIO(body)) as stream:
                body = stream.read(EXPANDED_LIMIT + 1)
            if len(body) > EXPANDED_LIMIT:
                raise ForecastBodyError('prospective_expanded_request_too_large', 413)
        data = json.loads(body)
    except (OSError, EOFError, UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise ForecastBodyError('invalid_json', 400) from exc
    # Preserve the historical uncompressed V1 contract; gzip permits the same
    # complete evidence with an explicit expanded-size limit, never truncation.
    if (encoding == 'identity' and len(body) > LEGACY_PLAIN_LIMIT
            and (not isinstance(data, dict) or _namespace_view(data.get('schema_version')) != _namespace_view('longitudinal-prospective-request-v2'))):
        raise ForecastBodyError('prospective_request_too_large', 413)
    return data
