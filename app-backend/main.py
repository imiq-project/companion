"""Compatible local entrypoint. Production WSGI uses application:create_app()."""
from application import create_app

app = create_app()

if __name__ == '__main__':
    from infrastructure.service_config import settings
    runtime = settings()
    app.run(host=runtime.host, port=runtime.backend_port, debug=False)
