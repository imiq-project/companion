"""Validated operational settings. Environment overrides the optional local file."""
from dataclasses import dataclass
import math
import os
from pathlib import Path
import shutil
from urllib.parse import urlsplit
from dotenv import load_dotenv

from resources import PROJECT_ROOT as ROOT, BACKEND_ENV_FILE


def load_environment():
    load_dotenv(BACKEND_ENV_FILE, override=False)


def positive_number(name, default, value=None):
    try:result = float(os.environ.get(name, default) if value is None else value)
    except (ValueError,TypeError):raise ValueError(f'{name} must be positive and finite') from None
    if not math.isfinite(result) or result <= 0:
        raise ValueError(f'{name} must be positive and finite')
    return result


def positive_integer(name, default):
    raw = os.environ.get(name, str(default))
    try:
        result = int(raw)
    except (ValueError, TypeError):
        raise ValueError(f'{name} must be a positive integer') from None
    if result <= 0:
        raise ValueError(f'{name} must be a positive integer')
    return result


def provider_url(name, default='', value=None):
    result = (os.environ.get(name, default) if value is None else value).rstrip('/')
    if not result:return result
    parsed = urlsplit(result)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError(f'{name} must be an HTTP(S) URL without credentials, query or fragment')
    return result


@dataclass(frozen=True)
class Settings:
    root: Path
    state: Path
    java: str
    otp_jar: Path
    backend_port: int
    otp_port: int
    realtime_port: int
    max_body_bytes: int
    max_inflight: int
    provider_timeout: float
    tile_timeout: float
    provider_parallelism: int
    host: str
    api_token: str

    @property
    def releases(self):return self.state/'transit-releases'
    @property
    def active_pointer(self):return self.state/'transit-active.json'


def settings(*, load_file=True):
    if load_file:load_environment()
    if os.environ.get('COMPANION_SERVICE_MANAGER','processes') not in ('processes','systemd'):
        raise ValueError('COMPANION_SERVICE_MANAGER must be processes or systemd')
    state = Path(os.environ.get('COMPANION_STATE_DIR', ROOT/'runtime/state')).resolve()
    java = os.environ.get('COMPANION_JAVA', '')
    if not java:
        studio = 'D:/Android Studio/jbr' if os.name=='nt' and Path('D:/Android Studio/jbr/bin/java.exe').is_file() else None
        java_home = os.environ.get('COMPANION_JAVA_HOME') or studio or os.environ.get('JAVA_HOME')
        candidate = Path(java_home)/'bin'/('java.exe' if os.name=='nt' else 'java') if java_home else None
        java = str(candidate) if candidate and candidate.is_file() else (shutil.which('java') or '')
    for name, default in (('COMPANION_ROUTING_BASE_URL',''), ('COMPANION_GRAPHHOPPER_BASE_URL',''), ('COMPANION_OTP_BASE_URL','')):
        provider_url(name, default)
    for name,default in (('COMPANION_GEOMETRY_TIMEOUT_SECONDS',15),('COMPANION_OTP_TIMEOUT_SECONDS',20),
                         ('COMPANION_WEATHER_TIMEOUT_SECONDS',6),('COMPANION_GEOCODING_TIMEOUT_SECONDS',12)):
        positive_number(name,default)
    positive_integer('COMPANION_MAX_BATCH_REQUESTS',16)
    ports = [positive_integer(name, default) for name, default in
             (('PORT',8077),('COMPANION_OTP_PORT',8091),('COMPANION_REALTIME_PORT',8092))]
    if len(set(ports))!=3 or any(port>65535 for port in ports):
        raise ValueError('API, OTP and realtime ports must be distinct and <=65535')
    return Settings(ROOT,state,java,Path(os.environ.get('COMPANION_OTP_JAR',ROOT/'runtime/otp-shaded-2.7.0.jar')).resolve(),
                    *ports, positive_integer('COMPANION_MAX_BODY_BYTES',64*1024*1024),
                    positive_integer('COMPANION_MAX_INFLIGHT_COMPUTE',1),
                    positive_number('COMPANION_ROUTING_TIMEOUT_SECONDS',120),
                    positive_number('COMPANION_TILE_TIMEOUT_SECONDS',12),
                    positive_integer('COMPANION_PROVIDER_PARALLELISM',3),
                    os.environ.get('COMPANION_BIND_HOST','127.0.0.1'),os.environ.get('COMPANION_API_TOKEN',''))
