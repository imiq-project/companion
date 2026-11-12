"""Visible OSM tiles for USB clients, cached for at least seven days."""
import base64
import tempfile
import threading
import time
import os
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError

from flask import jsonify
from infrastructure.service_config import positive_number, positive_integer
from infrastructure.service_runtime import KeyedLocks

CACHE = Path(tempfile.gettempdir()) / 'companion-map-tiles-v1'
TTL = 7 * 24 * 60 * 60
LOCK = KeyedLocks()
DOWNLOAD_CAPACITY = threading.BoundedSemaphore(positive_integer('COMPANION_TILE_MAX_DOWNLOADS',4))


def register_map_tiles(app):
    @app.get('/api/companion/map-tiles/<int:z>/<int:x>/<int:y>')
    def map_tile(z, x, y):
        if not 0 <= z <= 19 or not 0 <= x < 2 ** z or not 0 <= y < 2 ** z:
            return jsonify(error='invalid_map_tile'), 400
        tile_path = CACHE / str(z) / str(x) / f'{y}.png'
        try:
            with LOCK.hold((z,x,y)):
                if tile_path.exists() and time.time() - tile_path.stat().st_mtime < TTL:
                    data = tile_path.read_bytes()
                else:
                    upstream = Request(
                        f'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                        headers={'User-Agent': 'Companion/0.2 (+https://github.com/ghaefner92/Companion)',
                                 'Accept': 'image/png'},
                    )
                    with DOWNLOAD_CAPACITY:
                        with urlopen(upstream, timeout=positive_number('COMPANION_TILE_TIMEOUT_SECONDS',12)) as response:
                            data = response.read(2_000_001)
                    if len(data)>2_000_000:
                        return jsonify(error='invalid_map_tile_response'),502
                    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
                        return jsonify(error='invalid_map_tile_response'), 502
                    tile_path.parent.mkdir(parents=True, exist_ok=True)
                    temp=tile_path.with_name(tile_path.name+'.'+uuid.uuid4().hex+'.tmp')
                    try:
                        temp.write_bytes(data)
                        os.replace(temp,tile_path)
                    finally:temp.unlink(missing_ok=True)
            response = jsonify(data_url='data:image/png;base64,' + base64.b64encode(data).decode('ascii'))
            response.headers['Cache-Control'] = f'private, max-age={TTL}'
            return response
        except (URLError, TimeoutError, OSError):
            return jsonify(error='map_tiles_unavailable'), 503
