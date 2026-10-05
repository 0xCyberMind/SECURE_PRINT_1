import math
from typing import List, Optional
from fastapi import APIRouter, Depends, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import Device, Shop
from app.repositories.shop_repo import ShopRepository, PrinterRepository
from app.schemas.shop import (
    DevicePrintKeyResponse,
    ShopResponse,
    PermanentQrResponse,
    ShopCreateRequest,
    NearbyShopResponse,
    PrinterResponse,
)
from app.api.deps import get_current_user, require_roles, AuthPrincipal

router = APIRouter()


@router.get("/{shop_id}/print-keys", response_model=List[DevicePrintKeyResponse])
async def get_shop_print_keys(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
) -> List[DevicePrintKeyResponse]:
    shop = await ShopRepository(db).get_by_id(shop_id)
    if not shop or shop.status != "ACTIVE" or not shop.is_verified:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message="Active verified shop not found",
        )

    result = await db.execute(
        select(Device).where(
            Device.shop_id == shop_id,
            Device.is_active.is_(True),
            Device.encryption_public_key.isnot(None),
        )
    )
    return [
        DevicePrintKeyResponse(device_id=device.id, public_key=device.encryption_public_key)
        for device in result.scalars().all()
    ]


def calculate_haversine_distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Computes the great-circle distance between two geographic coordinates in kilometers.
    """
    r = 6371.0  # Earth's mean radius in km
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (
        math.sin(dlat / 2.0) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(dlon / 2.0) ** 2
    )
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return round(r * c, 2)


@router.get("", response_model=List[ShopResponse])
async def list_shops(
    current_user: AuthPrincipal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session)
) -> List[ShopResponse]:
    shop_repo = ShopRepository(db)
    # Shop owner sees their own shops; regular users see verified shops
    if current_user.role == UserRole.SHOP_OPERATOR.value:
        result = await db.execute(
            select(Shop).where(Shop.owner_id == current_user.user_id)
        )
        shops = list(result.scalars().all())
    else:
        shops = await shop_repo.list_verified_shops()
    return [ShopResponse.model_validate(s) for s in shops]


@router.get("/nearby", response_model=List[NearbyShopResponse])
async def get_nearby_shops(
    latitude: Optional[float] = Query(None, description="User latitude in degrees (-90 to 90)"),
    longitude: Optional[float] = Query(None, description="User longitude in degrees (-180 to 180)"),
    lat: Optional[float] = Query(None, description="Alternative latitude param"),
    lng: Optional[float] = Query(None, description="Alternative longitude param"),
    radius: float = Query(default=25.0, description="Discovery radius in kilometers (0 < radius <= 100)"),
    db: AsyncSession = Depends(get_db_session)
) -> List[NearbyShopResponse]:
    eff_lat = latitude if latitude is not None else lat
    eff_lng = longitude if longitude is not None else lng

    if eff_lat is None:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Missing required parameter: latitude"
        )
    if eff_lng is None:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Missing required parameter: longitude"
        )

    # 1. Validate coordinates
    if eff_lat < -90.0 or eff_lat > 90.0:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Invalid coordinates: latitude must be between -90 and 90 degrees"
        )
    if eff_lng < -180.0 or eff_lng > 180.0:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Invalid coordinates: longitude must be between -180 and 180 degrees"
        )

    # 2. Validate radius
    if radius <= 0.0 or radius > 100.0:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Invalid radius: radius must be greater than 0 and at most 100 km"
        )

    # 3. Query only registered, active, and verified shops
    stmt = (
        select(Shop)
        .where(
            Shop.status == "ACTIVE",
            Shop.is_verified == True,
            Shop.latitude.isnot(None),
            Shop.longitude.isnot(None)
        )
    )
    result = await db.execute(stmt)
    active_shops = result.scalars().all()

    # 4. Compute distances and filter within radius
    nearby_list: List[NearbyShopResponse] = []
    for shop in active_shops:
        dist = calculate_haversine_distance_km(eff_lat, eff_lng, shop.latitude, shop.longitude)
        if dist <= radius:
            # 5. Return only sanitized public shop information (never leak internal devices/keys/owners)
            nearby_list.append(
                NearbyShopResponse(
                    shop_id=shop.id,
                    id=shop.id,
                    name=shop.name,
                    address=shop.address,
                    latitude=shop.latitude,
                    longitude=shop.longitude,
                    distance_km=dist,
                    distanceKm=dist,
                    status=shop.status
                )
            )

    # Sort nearest first
    nearby_list.sort(key=lambda s: s.distance_km)
    return nearby_list


@router.post("", response_model=ShopResponse, status_code=status.HTTP_201_CREATED)
async def create_shop(
    payload: ShopCreateRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.ADMIN]))
) -> ShopResponse:
    shop_repo = ShopRepository(db)
    import uuid
    shop_id = f"SHOP-{uuid.uuid4().hex[:6].upper()}"
    qr_payload = f"privprint://shop?id={shop_id}"

    shop = await shop_repo.create(
        id=shop_id,
        name=payload.name,
        owner_id=principal.user_id,
        address=payload.address,
        latitude=payload.latitude,
        longitude=payload.longitude,
        status="ACTIVE",
        is_verified=True,
        is_online=principal.role == UserRole.ADMIN,
        supports_color=payload.supports_color,
        supports_duplex=payload.supports_duplex,
        permanent_qr_payload=qr_payload
    )
    await db.commit()
    return ShopResponse.model_validate(shop)


@router.post("/{shop_id}/approve", response_model=ShopResponse)
async def approve_shop(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN])),
) -> ShopResponse:
    shop = await db.get(Shop, shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {shop_id} not found"
        )

    shop.status = "ACTIVE"
    shop.is_verified = True
    shop.is_online = True
    await db.commit()
    await db.refresh(shop)
    return ShopResponse.model_validate(shop)


@router.get("/{shop_id}", response_model=ShopResponse)
async def get_shop_by_id(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session)
) -> ShopResponse:
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {shop_id} not found"
        )
    return ShopResponse.model_validate(shop)


@router.get("/{shop_id}/permanent-qr", response_model=PermanentQrResponse)
async def get_permanent_qr(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session)
) -> PermanentQrResponse:
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {shop_id} not found"
        )

    # QR format: privprint://shop?id=SHOP-ID (contains no secret)
    qr_format = f"privprint://shop?id={shop.id}"
    return PermanentQrResponse(
        shop_id=shop.id,
        qr_payload=qr_format,
        format="privprint://shop?id={shop_id}"
    )


@router.get("/{shop_id}/printers", response_model=List[PrinterResponse])
async def get_shop_printers(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session)
) -> List[PrinterResponse]:
    """
    Get all discovered/installed printers associated with the shop.
    Returns status (READY, BUSY, OFFLINE, ERROR, UNKNOWN), driver info, and connection capabilities.
    """
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {shop_id} not found"
        )

    printer_repo = PrinterRepository(db)
    printers = await printer_repo.list_by_shop(shop_id)
    return [PrinterResponse.model_validate(p) for p in printers]
