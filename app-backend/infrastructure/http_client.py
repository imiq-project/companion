"""Bounded JSON decoding for provider clients, independent of Flask."""
import json


def read_json_response(response, limit):
    """Bound provider response bytes before allocating a parsed JSON document."""
    body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError('Provider response exceeds byte limit')
    try:
        return json.loads(body)
    except (UnicodeError, RecursionError) as exc:
        raise ValueError('Invalid provider JSON') from exc


