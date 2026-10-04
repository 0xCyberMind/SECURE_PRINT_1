import logging
import sys
import json
import re
from typing import Any, Dict
from datetime import datetime

# Regex pattern for redacting sensitive authentication & cryptographic material in logs
SENSITIVE_PATTERNS = [
    (re.compile(r'(?i)(["\']?(?:password|passwd|pwd|secret|api_key|access_token|refresh_token|token|private_key|symmetric_key|iv_hex)["\']?\s*[:=]\s*["\']?)([^"\'\s,}{]+)(["\']?)'), r'\1[REDACTED]\3'),
    (re.compile(r'(?i)(Bearer\s+)[A-Za-z0-9_\-\.]+'), r'\1[REDACTED]'),
    (re.compile(r'(?i)(Authorization:\s*Bearer\s+)[^\r\n]+'), r'\1[REDACTED]')
]


def redact_sensitive_data(message: str) -> str:
    """
    Strips passwords, tokens, API keys, and cryptographic material from log strings.
    """
    if not isinstance(message, str):
        return str(message)
    redacted = message
    for pattern, repl in SENSITIVE_PATTERNS:
        redacted = pattern.sub(repl, redacted)
    return redacted


class StructuredJsonFormatter(logging.Formatter):
    """
    Format logs as JSON objects for centralized log ingestion (CloudWatch, Datadog, ELK).
    Automatically scrubs sensitive fields and authentication tokens.
    """
    def format(self, record: logging.LogRecord) -> str:
        raw_message = record.getMessage()
        clean_message = redact_sensitive_data(raw_message)

        log_obj: Dict[str, Any] = {
            "timestamp": datetime.utcfromtimestamp(record.created).isoformat() + "Z",
            "level": record.levelname,
            "logger": record.name,
            "message": clean_message,
        }

        # Include extra fields (like request_id)
        if hasattr(record, "request_id"):
            log_obj["request_id"] = getattr(record, "request_id")
        if record.exc_info:
            clean_exc = redact_sensitive_data(self.formatException(record.exc_info))
            log_obj["exception"] = clean_exc

        return json.dumps(log_obj)


def setup_logging(debug: bool = False) -> None:
    level = logging.DEBUG if debug else logging.INFO
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(StructuredJsonFormatter())

    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    # Clear existing handlers to avoid duplicates
    root_logger.handlers = [handler]

    # Silence noisy loggers
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


logger = logging.getLogger("privprint")
