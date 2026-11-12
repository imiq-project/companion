"""Shared Blueprint identity and privacy-safe API logger."""
import logging
import os
from flask import Blueprint

logging.basicConfig(level=os.environ.get('COMPANION_LOG_LEVEL', 'INFO'))
logger = logging.getLogger('companion_api')
api = Blueprint('companion', __name__)
