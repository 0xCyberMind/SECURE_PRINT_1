import asyncio
import base64
import hashlib
import logging
from urllib.parse import unquote
from typing import List, Dict, Any, Optional
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from windows_agent.config import AgentConfig
from windows_agent.secure_store import SecureCredentialStore
from windows_agent.printer_spooler import (
    PrinterSpoolerManager,
    SpoolerJobRecord,
    SpoolerException,
    PrinterOfflineError,
    PrinterUnavailableError,
    PrinterRemovedError,
    SpoolerFailureError,
    InvalidDocumentError,
    SpoolerPermissionError,
)
from windows_agent.api_client import WindowsAgentApiClient
from windows_agent.realtime_client import WindowsAgentRealtimeClient, ConnectionState

logger = logging.getLogger("WindowsAgentService")


class WindowsAgentService:
    """
    Main Background Agent Engine for Windows Shop Station.
    Implements the complete hardened production flow:
    - Outbound WSS only, zero inbound connections
    - Automatic reconnect with exponential backoff and jitter
    - Periodic heartbeat & stale device prevention
    - Authentication renewal
    - Comprehensive 7-step reconnect reconciliation
    - Missed event recovery (REST/DB authoritative)
    - Duplicate event deduplication
    - Real Windows Print Spooler tracking and verified memory cleanup
    """

    def __init__(self, config: Optional[AgentConfig] = None):
        self.config = config or AgentConfig.load()
        self.secure_store = SecureCredentialStore()
        self.spooler = PrinterSpoolerManager()
        self.api_client = WindowsAgentApiClient(self.config)
        self.realtime_client = WindowsAgentRealtimeClient(
            config=self.config,
            on_event_callback=self.on_realtime_event,
            on_reconnect_callback=self.reconcile_on_reconnect
        )
        self.is_running = False
        self._background_tasks_started = False
        self._operator_token: Optional[str] = None
        self._pending_operator_shops: Dict[str, str] = {}
        self.active_jobs: Dict[str, Dict[str, Any]] = {}
        self.job_history: List[Dict[str, Any]] = []
        self.audit_log: List[Dict[str, Any]] = []
        self._station_private_key = None
        self.encryption_key_registered = False

    @property
    def connection_state(self) -> ConnectionState:
        return self.realtime_client.state

    def log_audit(self, event_type: str, details: str, severity: str = "INFO"):
        event = {
            "timestamp": asyncio.get_event_loop().time(),
            "eventType": event_type,
            "details": details,
            "severity": severity
        }
        self.audit_log.append(event)
        logger.info(f"[{severity}] AUDIT: {event_type} - {details}")

    async def initialize(self):
        creds = self.secure_store.retrieve_credentials()
        if creds:
            self.config.device_id = creds.get("device_id")
            self.config.api_key = creds.get("api_key")
            self.config.access_token = creds.get("access_token")
            self.config.refresh_token = creds.get("refresh_token")
            self.config.shop_id = creds.get("shop_id") or self.config.shop_id
            self.api_client.access_token = self.config.access_token
            self.api_client.refresh_token = self.config.refresh_token
            private_key_pem = creds.get("station_private_key_pem")
            if private_key_pem:
                self._station_private_key = serialization.load_pem_private_key(
                    private_key_pem.encode("ascii"),
                    password=None,
                )
            self.log_audit("CREDENTIALS_RESTORED", "Windows station credentials restored")

        if self._station_private_key is None:
            self._station_private_key = rsa.generate_private_key(
                public_exponent=65537,
                key_size=3072,
            )
        self.log_audit("SYSTEM_INIT", "Windows Shop Station Agent Initialized")

    def _station_private_key_pem(self) -> str:
        if self._station_private_key is None:
            raise RuntimeError("Station encryption key has not been initialized")
        return self._station_private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode("ascii")

    def _station_public_key_base64(self) -> str:
        if self._station_private_key is None:
            raise RuntimeError("Station encryption key has not been initialized")
        public_key_der = self._station_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return base64.b64encode(public_key_der).decode("ascii")

    async def _register_print_key(self) -> None:
        if not self.config.device_id or not self.config.access_token:
            self.encryption_key_registered = False
            raise RuntimeError("Station must be authenticated before registering its print key")
        try:
            await self.api_client.register_device_print_key(
                self.config.device_id,
                self._station_public_key_base64(),
            )
        except Exception:
            self.encryption_key_registered = False
            raise
        self.encryption_key_registered = True
        self.secure_store.store_credentials(
            device_id=self.config.device_id,
            api_key=self.config.api_key or "",
            access_token=self.config.access_token,
            refresh_token=self.config.refresh_token or "",
            shop_id=self.config.shop_id,
            station_private_key_pem=self._station_private_key_pem(),
        )

    async def register_or_authenticate(self, operator_token: Optional[str] = None):
        """
        Step 1 & 2 of Station Auth Lifecycle.
        """
        if not self.config.access_token:
            try:
                reg_info = await self.api_client.register_device(
                    shop_id=self.config.shop_id,
                    device_name=self.config.device_name,
                    operator_token=operator_token
                )
                self.log_audit("DEVICE_REGISTERED", f"Registered new device: {reg_info.get('device_id')}")

                auth_info = await self.api_client.authenticate_device(
                    device_id=reg_info.get("device_id"),
                    api_key=reg_info.get("api_key")
                )
                self.config.device_id = reg_info.get("device_id")
                self.config.api_key = reg_info.get("api_key")
                self.config.access_token = auth_info.get("accessToken") or auth_info.get("access_token")
                self.config.refresh_token = auth_info.get("refreshToken") or auth_info.get("refresh_token")
                self.secure_store.store_credentials(
                    device_id=self.config.device_id,
                    api_key=self.config.api_key,
                    access_token=self.config.access_token,
                    refresh_token=self.config.refresh_token,
                    shop_id=self.config.shop_id,
                    station_private_key_pem=self._station_private_key_pem(),
                )
                self.log_audit("DEVICE_AUTHENTICATED", f"Authenticated as PRINT_DEVICE: {self.config.device_id}")
            except Exception as e:
                self.log_audit("AUTH_ERROR", f"Device registration/auth error: {e}", severity="WARNING")
        else:
            # Refresh if token might be expiring
            try:
                if self.config.refresh_token:
                    auth_info = await self.api_client.refresh_auth_tokens()
                    self.secure_store.store_credentials(
                        device_id=self.config.device_id,
                        api_key=self.config.api_key,
                        access_token=auth_info.get("accessToken"),
                        refresh_token=auth_info.get("refreshToken"),
                        shop_id=self.config.shop_id,
                        station_private_key_pem=self._station_private_key_pem(),
                    )
                    self.config.save()
                    self.log_audit("TOKEN_REFRESHED", "Successfully refreshed JWT access token")
            except Exception as e:
                logger.debug(f"Token refresh check: {e}")
        await self._register_print_key()

    async def sync_discovered_printers(self):
        try:
            printers = self.spooler.discover_local_printers()
            if self.config.access_token:
                await self.api_client.sync_printers(self.config.shop_id, printers)
                self.log_audit("PRINTERS_SYNCED", f"Synced {len(printers)} Windows spooler printers to cloud backend")
            else:
                self.log_audit("PRINTERS_DISCOVERED", f"Discovered {len(printers)} local Windows spooler printers")
        except Exception as e:
            self.log_audit("PRINTER_SYNC_WARN", f"Local printer discovery sync note: {e}", severity="WARNING")

    async def _set_operator_shops(self, token_response: Dict[str, Any]) -> List[Dict[str, str]]:
        operator_token = token_response.get("access_token")
        if not operator_token:
            raise RuntimeError("Server response did not include an operator access token.")

        user = token_response.get("user") or {}
        if user.get("role") != "SHOP_OPERATOR":
            raise RuntimeError("This account is not registered as a Xerox shop operator.")

        shops = await self.api_client.list_operator_shops(operator_token)
        self._operator_token = operator_token
        self._pending_operator_shops = {
            str(shop["id"]): str(shop.get("name") or "Xerox shop")
            for shop in shops
            if shop.get("id")
        }
        if not self._pending_operator_shops:
            self._operator_token = None
            raise RuntimeError("No shop is linked to this operator account.")
        return [
            {"id": shop_id, "name": name}
            for shop_id, name in self._pending_operator_shops.items()
        ]

    async def login_operator(self, email: str, password: str) -> List[Dict[str, str]]:
        token_response = await self.api_client.login_operator(email.strip(), password)
        return await self._set_operator_shops(token_response)

    async def register_operator(
        self,
        email: str,
        password: str,
        full_name: str,
        shop_name: str,
        shop_address: str,
    ) -> List[Dict[str, str]]:
        token_response = await self.api_client.register_operator(
            email.strip(),
            password,
            full_name.strip(),
        )
        operator_token = token_response.get("access_token")
        if not operator_token:
            raise RuntimeError("Server response did not include an operator access token.")

        user = token_response.get("user") or {}
        if user.get("role") != "SHOP_OPERATOR":
            raise RuntimeError("Server did not create a Xerox shop operator account.")

        shop = await self.api_client.create_operator_shop(
            shop_name.strip(),
            shop_address.strip(),
            operator_token,
        )
        self._operator_token = operator_token
        shop_id = str(shop["id"])
        shop_name = str(shop.get("name") or "Xerox shop")
        self._pending_operator_shops = {shop_id: shop_name}
        return [{"id": shop_id, "name": shop_name}]

    async def connect_operator_shop(self, shop_id: str) -> None:
        if not self._operator_token or shop_id not in self._pending_operator_shops:
            raise RuntimeError("Please sign in as the shop operator and select one of their shops.")

        self.config.shop_id = shop_id
        registration = await self.api_client.register_device(
            shop_id=shop_id,
            device_name=self.config.device_name,
            operator_token=self._operator_token,
        )
        device_id = registration.get("device_id")
        api_key = registration.get("api_key")
        if not device_id or not api_key:
            raise RuntimeError("Server did not return valid station device credentials.")

        auth = await self.api_client.authenticate_device(device_id, api_key)
        access_token = auth.get("access_token") or auth.get("accessToken")
        refresh_token = auth.get("refresh_token") or auth.get("refreshToken")
        if not access_token or not refresh_token:
            raise RuntimeError("Server did not return valid station authentication tokens.")

        self.config.device_id = device_id
        self.config.api_key = api_key
        self.config.access_token = access_token
        self.config.refresh_token = refresh_token
        self.api_client.access_token = access_token
        self.api_client.refresh_token = refresh_token
        self.secure_store.store_credentials(
            device_id=device_id,
            api_key=api_key,
            access_token=access_token,
            refresh_token=refresh_token,
            shop_id=shop_id,
            station_private_key_pem=self._station_private_key_pem(),
        )
        await self._register_print_key()
        self.config.save()
        self._operator_token = None
        self._pending_operator_shops.clear()
        await self._start_authenticated_tasks()

    async def _start_authenticated_tasks(self) -> None:
        self.is_running = True
        await self.reconcile_on_reconnect()
        if self._background_tasks_started:
            return
        self._background_tasks_started = True
        asyncio.create_task(self._heartbeat_rest_loop())
        asyncio.create_task(self.poll_queue_loop())
        asyncio.create_task(self.realtime_client.start())

    async def reconcile_on_reconnect(self):
        """
        Authoritative 7-step reconnect sequence:
        1. Authenticate (refresh or re-auth).
        2. Register device / verify credentials.
        3. Fetch current device state.
        4. Fetch pending jobs (missed event recovery).
        5. Reconcile job states (DB authoritative).
        6. Refresh printer list.
        7. Realtime channel subscription confirmed.
        """
        self.log_audit("RECONNECT_SEQUENCE_START", "Starting 7-step connection recovery reconciliation")

        try:
            # 1 & 2. Authenticate & Ensure Device Registration
            await self.register_or_authenticate()

            # 3. Fetch Current Device State
            if self.config.device_id:
                try:
                    dev_state = await self.api_client.get_device_state(self.config.device_id)
                    self.log_audit(
                        "DEVICE_STATE_VERIFIED",
                        f"Device {self.config.device_id} status: {dev_state.get('status')}"
                    )
                except Exception as e:
                    self.log_audit("DEVICE_STATE_WARN", f"Device state check note: {e}", severity="WARNING")

            # 4. Fetch Pending Jobs (Missed Event Recovery)
            queue = []
            if self.config.access_token:
                try:
                    queue = await self.api_client.get_print_queue(self.config.shop_id)
                    self.log_audit("QUEUE_RECONCILED", f"Fetched authoritative queue: {len(queue)} job(s)")
                except Exception as e:
                    self.log_audit("QUEUE_FETCH_ERROR", f"Error fetching queue on reconnect: {e}", severity="WARNING")

            # 5. Reconcile Job States
            active_ids_in_db = set()
            for job in queue:
                j_id = job.get("jobId") or job.get("id")
                j_status = job.get("status")
                if j_id:
                    active_ids_in_db.add(j_id)
                    if j_status == "AUTHORIZED" and j_id not in self.active_jobs:
                        if self.config.auto_print_enabled:
                            self.log_audit("MISSED_EVENT_RECOVERED", f"Resuming missed job {j_id} from DB")
                            asyncio.create_task(self.process_print_job(j_id))

            # 6. Refresh Printer List
            await self.sync_discovered_printers()

            # 7. Completed
            self.log_audit("RECONNECT_SEQUENCE_COMPLETE", "7-step reconnect reconciliation finished successfully")

        except Exception as e:
            self.log_audit("RECONNECT_ERROR", f"Error during reconnect sequence: {e}", severity="ERROR")

    async def on_realtime_event(self, event_type: str, event_data: Dict[str, Any]):
        logger.info(f"Agent Processing Event {event_type}: {event_data}")
        if event_type == "JOB_AUTHORIZED":
            job_id = event_data.get("job_id")
            if job_id and self.config.auto_print_enabled:
                await self.process_print_job(job_id)

    def _ephemeral_decrypt(self, content: Dict[str, Any]) -> bytearray:
        if self._station_private_key is None:
            raise RuntimeError("Station decryption key is unavailable")
        ciphertext = content.get("ciphertext")
        wrapped_key = content.get("wrapped_key")
        iv_hex = content.get("iv")
        expected_hash = content.get("sha256")
        if not all((ciphertext, wrapped_key, iv_hex, expected_hash)):
            raise InvalidDocumentError("Encrypted document metadata is incomplete")
        if hashlib.sha256(ciphertext).hexdigest() != expected_hash.lower():
            raise InvalidDocumentError("Encrypted document failed SHA-256 integrity validation")

        key_buf = bytearray(
            self._station_private_key.decrypt(
                base64.b64decode(wrapped_key, validate=True),
                padding.OAEP(
                    mgf=padding.MGF1(algorithm=hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            )
        )
        try:
            if len(key_buf) != 32:
                raise InvalidDocumentError("Unwrapped document key has an invalid length")
            iv = bytes.fromhex(iv_hex)
            if len(iv) != 12:
                raise InvalidDocumentError("Document AES-GCM nonce has an invalid length")
            return bytearray(AESGCM(bytes(key_buf)).decrypt(iv, ciphertext, None))
        finally:
            key_buf[:] = b"\x00" * len(key_buf)

    async def process_print_job(self, job_id: str):
        if job_id in self.active_jobs:
            return

        target_job: Optional[Dict[str, Any]] = None
        decrypted_doc: Optional[bytearray] = None
        try:
            self.log_audit("JOB_FETCHED", f"Fetching job details for {job_id}")
            queue = await self.api_client.get_print_queue(self.config.shop_id)
            target_job = next((j for j in queue if j.get("jobId") == job_id or j.get("id") == job_id), None)
            if not target_job:
                raise RuntimeError(f"Authorized print job {job_id} was not found in the shop queue")

            job_status = target_job.get("status")
            if job_status not in ("AUTHORIZED", "PRINTING"):
                raise SpoolerFailureError(f"Job {job_id} is in invalid state: {job_status}")

            copies = (
                target_job.get("requested_copies")
                or target_job.get("copies_authorized")
                or target_job.get("copiesAuthorized")
                or 1
            )
            if copies <= 0:
                raise InvalidDocumentError(f"Job {job_id} specifies 0 authorized copies")

            job_shop = target_job.get("shopId") or target_job.get("shop_id")
            if job_shop != self.config.shop_id:
                raise SpoolerPermissionError(f"Job shop {job_shop} does not match station shop {self.config.shop_id}")

            if not self.config.device_id:
                raise SpoolerPermissionError("Station device identity is not configured")

            target_printer_id = target_job.get("printerId") or target_job.get("printer_id") or "PRN-HP-01"
            validated_printer = self.spooler.validate_printer_for_job(target_printer_id)
            self.log_audit(
                "PRINTER_VALIDATED",
                f"Target printer validated: {validated_printer['name']} ({validated_printer['status']})"
            )

            self.active_jobs[job_id] = target_job

            document_id = target_job.get("documentId") or target_job.get("document_id")
            if not document_id:
                raise InvalidDocumentError(f"Job {job_id} has no document ID")
            encrypted_content = await self.api_client.get_print_content(document_id, job_id)
            self.log_audit("CIPHERTEXT_RETRIEVED", f"Retrieved encrypted ciphertext for job {job_id}")

            decrypted_doc = self._ephemeral_decrypt(encrypted_content)
            self.log_audit("EPHEMERAL_DECRYPTION", f"Decrypted payload into RAM for job {job_id}")

            await self.api_client.start_printing(job_id)
            target_job["status"] = "PRINTING"
            filename = unquote(encrypted_content["filename"] or "Document.pdf")

            def on_spool_progress(copy_num: int, total: int, state: str):
                target_job["copiesPrinted"] = copy_num
                target_job["spoolerState"] = state
                self.log_audit("SPOOLER_PROGRESS", f"Job {job_id} [{state}]: Copy {copy_num}/{total}")

            spooler_record = self.spooler.print_document(
                printer_id=target_printer_id,
                document_bytes=decrypted_doc,
                document_name=filename,
                copies=copies,
                progress_callback=on_spool_progress
            )

            if spooler_record.copies_printed > 0:
                try:
                    await self.api_client.increment_copy(job_id, delta=spooler_record.copies_printed)
                except Exception as e:
                    self.log_audit("COPY_SYNC_WARN", f"Could not sync printed copies for {job_id}: {e}", severity="WARNING")

            if spooler_record.status == "COMPLETED":
                target_job["status"] = "COMPLETED"
                target_job["verification_status"] = spooler_record.verification_status
                self.job_history.append(target_job)
                self.log_audit(
                    "PRINT_COMPLETED",
                    f"Job {job_id} completed via Windows Spooler ({spooler_record.verification_status})"
                )
            else:
                target_job["status"] = "FAILED"
                raise SpoolerFailureError(f"Spooler did not complete: {spooler_record.error_message or 'Spool error'}")

            if job_id in self.active_jobs:
                del self.active_jobs[job_id]
            try:
                await self.api_client.execute_cleanup(job_id)
                self.log_audit("CLEANUP_EXECUTED", f"Verified memory/storage cleanup executed for job {job_id}")
            except Exception as e:
                self.log_audit("CLEANUP_WARN", f"Cloud cleanup did not complete for {job_id}: {e}", severity="WARNING")

        except SpoolerException as e:
            self.log_audit("JOB_SPOOL_ERROR", f"Spooler error for {job_id} [{e.code}]: {e.message}", severity="ERROR")
            try:
                await self.api_client.fail_job(job_id, f"[{e.code}] {e.message}")
            except Exception as failure_error:
                self.log_audit("JOB_FAILURE_SYNC_ERROR", f"Could not report failure for {job_id}: {failure_error}", severity="ERROR")
            if job_id in self.active_jobs:
                del self.active_jobs[job_id]
        except Exception as e:
            self.log_audit("JOB_FAILED", f"Unexpected failure for {job_id}: {e}", severity="ERROR")
            try:
                await self.api_client.fail_job(job_id, str(e))
            except Exception as failure_error:
                self.log_audit("JOB_FAILURE_SYNC_ERROR", f"Could not report failure for {job_id}: {failure_error}", severity="ERROR")
            if job_id in self.active_jobs:
                del self.active_jobs[job_id]
        finally:
            if decrypted_doc is not None:
                decrypted_doc[:] = b"\x00" * len(decrypted_doc)

    async def _heartbeat_rest_loop(self):
        """
        Background periodic REST heartbeat loop for stale device prevention.
        """
        while self.is_running:
            try:
                if self.config.access_token and self.config.device_id:
                    if not self.encryption_key_registered:
                        try:
                            await self._register_print_key()
                        except Exception as e:
                            logger.warning("Station print-key registration retry failed: %s", e)
                    await self.api_client.send_heartbeat(self.config.device_id)
            except Exception as e:
                logger.debug(f"REST Heartbeat note: {e}")
            await asyncio.sleep(self.config.heartbeat_interval_sec)

    async def poll_queue_loop(self):
        while self.is_running:
            try:
                if self.config.access_token and self.config.auto_print_enabled:
                    queue = await self.api_client.get_print_queue(self.config.shop_id)
                    for job in queue:
                        status = job.get("status")
                        j_id = job.get("jobId") or job.get("id")
                        if status == "AUTHORIZED" and j_id not in self.active_jobs:
                            asyncio.create_task(self.process_print_job(j_id))
            except Exception as e:
                logger.debug(f"Queue poll iteration: {e}")
            await asyncio.sleep(10)

    async def start(self):
        self.is_running = True
        await self.initialize()
        if self.config.access_token and self.config.shop_id:
            await self._start_authenticated_tasks()

    def stop(self):
        self.is_running = False
        self.realtime_client.stop()
