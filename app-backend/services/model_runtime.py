"""Single process-local engine and lock shared by Passport execution paths."""
import threading
from model.compat import HOTCOCTv43

engine = HOTCOCTv43()
model_lock = threading.Lock()
