"""Linux WSGI configuration; capacity is explicitly per worker."""
import os

bind = os.environ.get('COMPANION_WSGI_BIND','127.0.0.1:8077')
workers = int(os.environ.get('COMPANION_WSGI_WORKERS','1'))
threads = int(os.environ.get('COMPANION_WSGI_THREADS','4'))
worker_class = 'gthread'
timeout = int(os.environ.get('COMPANION_WSGI_TIMEOUT','240'))
graceful_timeout = timeout
accesslog = None  # Application logs endpoint names, never raw URLs or payloads.
errorlog = '-'
capture_output = True
max_requests = 1000
max_requests_jitter = 100
if workers<1 or threads<2 or timeout<1:
    raise ValueError('Invalid WSGI worker, thread or timeout configuration')
if len(os.environ.get('COMPANION_API_TOKEN',''))<32:
    raise ValueError('Production requires COMPANION_API_TOKEN with at least 32 characters')
