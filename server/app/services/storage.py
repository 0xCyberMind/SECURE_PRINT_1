import hashlib
import time
from typing import Optional, Dict, Any
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode


class StorageService:
    """Abstract interface for S3-compatible Object Storage (MinIO / AWS S3 / Cloudflare R2)."""

    def generate_presigned_upload_url(self, object_key: str, expires_in: int = 300) -> str:
        raise NotImplementedError

    def generate_presigned_download_url(self, object_key: str, expires_in: int = 300) -> str:
        raise NotImplementedError

    def object_exists(self, object_key: str) -> bool:
        raise NotImplementedError

    def get_object_metadata(self, object_key: str) -> Dict[str, Any]:
        raise NotImplementedError

    def put_object_data(self, object_key: str, data: bytes, content_type: str = "application/octet-stream") -> str:
        raise NotImplementedError

    def get_object_data(self, object_key: str) -> bytes:
        raise NotImplementedError

    def delete_object(self, object_key: str) -> bool:
        raise NotImplementedError


class S3StorageService(StorageService):
    """Production S3-compatible storage implementation supporting MinIO and Cloud S3."""

    def __init__(self):
        self.bucket = settings.STORAGE_BUCKET_NAME
        s3_config = Config(
            signature_version="s3v4",
            s3={"addressing_style": "path"}  # Required for MinIO compatibility
        )
        endpoint = settings.STORAGE_ENDPOINT if settings.STORAGE_ENDPOINT else None
        self.client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            aws_access_key_id=settings.STORAGE_ACCESS_KEY,
            aws_secret_access_key=settings.STORAGE_SECRET_KEY,
            region_name=settings.STORAGE_REGION,
            use_ssl=settings.STORAGE_USE_SSL,
            config=s3_config
        )

    def generate_presigned_upload_url(self, object_key: str, expires_in: int = 300) -> str:
        try:
            url = self.client.generate_presigned_url(
                ClientMethod="put_object",
                Params={
                    "Bucket": self.bucket,
                    "Key": object_key,
                },
                ExpiresIn=expires_in,
                HttpMethod="PUT"
            )
            return url
        except ClientError as e:
            raise PrivPrintException(
                status_code=500,
                code=ErrorCode.STORAGE_ERROR,
                message=f"Failed to generate presigned upload URL: {str(e)}"
            )

    def generate_presigned_download_url(self, object_key: str, expires_in: int = 300) -> str:
        try:
            url = self.client.generate_presigned_url(
                ClientMethod="get_object",
                Params={
                    "Bucket": self.bucket,
                    "Key": object_key,
                },
                ExpiresIn=expires_in,
                HttpMethod="GET"
            )
            return url
        except ClientError as e:
            raise PrivPrintException(
                status_code=500,
                code=ErrorCode.STORAGE_ERROR,
                message=f"Failed to generate presigned download URL: {str(e)}"
            )

    def object_exists(self, object_key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=object_key)
            return True
        except ClientError:
            return False

    def get_object_metadata(self, object_key: str) -> Dict[str, Any]:
        try:
            resp = self.client.head_object(Bucket=self.bucket, Key=object_key)
            return {
                "size": resp.get("ContentLength", 0),
                "etag": resp.get("ETag", "").strip('"'),
                "content_type": resp.get("ContentType", "application/octet-stream")
            }
        except ClientError as e:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.STORAGE_ERROR,
                message=f"Object not found in storage: {str(e)}"
            )

    def put_object_data(self, object_key: str, data: bytes, content_type: str = "application/octet-stream") -> str:
        self.client.put_object(
            Bucket=self.bucket,
            Key=object_key,
            Body=data,
            ContentType=content_type
        )
        return object_key

    def get_object_data(self, object_key: str) -> bytes:
        resp = self.client.get_object(Bucket=self.bucket, Key=object_key)
        return resp["Body"].read()

    def delete_object(self, object_key: str) -> bool:
        try:
            self.client.delete_object(Bucket=self.bucket, Key=object_key)
            return True
        except ClientError:
            return False


class InMemoryStorageService(StorageService):
    """
    In-memory S3-compatible storage simulator for test suites and offline verification.
    Guarantees deterministic execution without requiring external MinIO daemons during CI.
    """

    def __init__(self):
        self._objects: Dict[str, bytes] = {}
        self._metadata: Dict[str, Dict[str, Any]] = {}

    def generate_presigned_upload_url(self, object_key: str, expires_in: int = 300) -> str:
        exp = int(time.time()) + expires_in
        return f"https://s3.privprint.internal/mock-upload/{object_key}?expires={exp}&sig=mock_sig"

    def generate_presigned_download_url(self, object_key: str, expires_in: int = 300) -> str:
        exp = int(time.time()) + expires_in
        return f"https://s3.privprint.internal/mock-download/{object_key}?expires={exp}&sig=mock_sig"

    def object_exists(self, object_key: str) -> bool:
        return object_key in self._objects

    def get_object_metadata(self, object_key: str) -> Dict[str, Any]:
        if object_key not in self._objects:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.NOT_FOUND,
                message=f"Object {object_key} not found in storage"
            )
        data = self._objects[object_key]
        sha256 = hashlib.sha256(data).hexdigest()
        return {
            "size": len(data),
            "etag": sha256,
            "content_type": self._metadata.get(object_key, {}).get("content_type", "application/octet-stream")
        }

    def put_object_data(self, object_key: str, data: bytes, content_type: str = "application/octet-stream") -> str:
        self._objects[object_key] = data
        self._metadata[object_key] = {
            "content_type": content_type,
            "created_at": time.time()
        }
        return object_key

    def get_object_data(self, object_key: str) -> bytes:
        if object_key not in self._objects:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.NOT_FOUND,
                message=f"Object {object_key} not found"
            )
        return self._objects[object_key]

    def delete_object(self, object_key: str) -> bool:
        self._objects.pop(object_key, None)
        self._metadata.pop(object_key, None)
        return True


# Global default instance
_default_storage_service: Optional[StorageService] = None


def get_storage_service() -> StorageService:
    global _default_storage_service
    if _default_storage_service is None:
        # If in testing or MinIO not specified, use InMemoryStorageService
        if settings.ENVIRONMENT.value == "development" and not settings.STORAGE_ENDPOINT.startswith("https"):
            _default_storage_service = InMemoryStorageService()
        else:
            _default_storage_service = S3StorageService()
    return _default_storage_service
