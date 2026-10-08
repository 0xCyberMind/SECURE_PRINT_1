from typing import Optional, List
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict, field_validator


class ShopCreateRequest(BaseModel):
    name: str = Field(..., min_length=2, max_length=255)
    address: str = Field(..., min_length=5)
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    supports_color: bool = True
    supports_duplex: bool = True
    history_retention_hours: int = Field(default=4, description="Default print history retention hours (allowed: 1, 2, 4, 6, 8)")

    @field_validator("history_retention_hours")
    @classmethod
    def validate_retention_hours(cls, v: int) -> int:
        if v not in (1, 2, 4, 6, 8):
            raise ValueError("History retention hours must be one of: 1, 2, 4, 6, 8")
        return v


class ShopSettingsResponse(BaseModel):
    shop_id: str
    history_retention_hours: int
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    address: Optional[str] = None
    location_enabled: bool = False


class ShopSettingsUpdateRequest(BaseModel):
    history_retention_hours: Optional[int] = Field(default=None, description="Print history retention hours (allowed: 1, 2, 4, 6, 8)")
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0, description="Shop latitude (-90 to 90)")
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0, description="Shop longitude (-180 to 180)")
    address: Optional[str] = Field(default=None, min_length=2, max_length=500, description="Shop physical address")
    clear_location: bool = Field(default=False, description="Flag to clear shop coordinates")

    @field_validator("history_retention_hours")
    @classmethod
    def validate_retention_hours(cls, v: Optional[int]) -> Optional[int]:
        if v is not None and v not in (1, 2, 4, 6, 8):
            raise ValueError("History retention hours must be one of: 1, 2, 4, 6, 8")
        return v


class ShopLocationUpdateRequest(BaseModel):
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0, description="Shop latitude (-90 to 90)")
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0, description="Shop longitude (-180 to 180)")
    address: Optional[str] = Field(default=None, min_length=2, max_length=500, description="Optional updated shop physical address")
    clear_location: bool = Field(default=False, description="Flag to clear shop coordinates")


class ShopResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    address: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    status: str
    is_verified: bool
    is_online: bool
    supports_color: bool
    supports_duplex: bool
    permanent_qr_payload: str
    history_retention_hours: int = 4
    created_at: datetime
    updated_at: datetime


class PermanentQrResponse(BaseModel):
    shop_id: str
    qr_payload: str
    format: str = "privprint://shop?id={shop_id}"


class NearbyShopResponse(BaseModel):
    shop_id: str
    id: Optional[str] = None
    name: str
    address: str
    latitude: float
    longitude: float
    distance_km: float
    distanceKm: Optional[float] = None
    status: str
    is_online: bool = True
    is_verified: bool = True
    supports_color: bool = True
    supports_duplex: bool = True
    permanent_qr_payload: Optional[str] = None

    def __init__(self, **data):
        if "id" not in data and "shop_id" in data:
            data["id"] = data["shop_id"]
        if "distanceKm" not in data and "distance_km" in data:
            data["distanceKm"] = data["distance_km"]
        super().__init__(**data)


class DeviceRegisterRequest(BaseModel):
    shop_id: str
    name: str = Field(..., min_length=2, max_length=255)
    os_info: str = Field(default="Windows 11 Pro 64-bit")
    app_version: str = Field(default="1.0.0")
    hardware_fingerprint: Optional[str] = None


class DeviceRegisterResponse(BaseModel):
    device_id: str
    shop_id: str
    name: str
    os_info: str
    app_version: str
    auth_state: str
    status: str
    api_key: str


class DeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    shop_id: str
    name: str
    device_type: str
    os_info: str
    app_version: str
    auth_state: str
    status: str
    last_seen_at: Optional[datetime] = None
    created_at: datetime


class DeviceAuthenticateRequest(BaseModel):
    device_id: str = Field(..., min_length=2)
    api_key: str = Field(..., min_length=8)


class DevicePrintKeyRequest(BaseModel):
    public_key: str = Field(..., min_length=128, max_length=4096)


class DevicePrintKeyResponse(BaseModel):
    device_id: str
    public_key: str


class SessionRevokeRequest(BaseModel):
    reason: Optional[str] = None


class SessionCreateRequest(BaseModel):
    shop_id: str


class SessionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: Optional[str] = None
    shop_id: str
    token: str
    nonce: str
    status: str
    expires_at: datetime
    created_at: datetime


class PrinterResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    shop_id: str
    name: str
    model: str = "NOT_REPORTED"
    driver_name: Optional[str] = "NOT_REPORTED"
    uri: Optional[str] = None
    connection_info: Optional[str] = "NOT_REPORTED"
    status: str = "READY"  # READY, BUSY, OFFLINE, ERROR, UNKNOWN
    is_default: bool = False
    is_online: bool = True
    supports_color: bool = True
    supports_duplex: bool = True
    supported_paper_sizes: Optional[str] = "A4, Letter"
    paper_tray_status: str = "READY"
    toner_level_percent: int = 100
    created_at: Optional[datetime] = None


class PrinterSelectRequest(BaseModel):
    shop_id: str
    printer_id: str
    session_id: Optional[str] = None
    device_id: Optional[str] = None


class PrinterSelectResponse(BaseModel):
    success: bool = True
    shop_id: str
    printer_id: str
    printer_name: str
    status: str
    driver_name: Optional[str] = None
    is_default: bool = False
    supports_color: bool = True
    supports_duplex: bool = True
    selected_at: datetime


class PrinterSyncRequest(BaseModel):
    shop_id: str
    printers: List[PrinterResponse]
