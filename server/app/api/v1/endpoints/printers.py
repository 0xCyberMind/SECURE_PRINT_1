from datetime import datetime, timezone
from typing import List, Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import Printer
from app.repositories.shop_repo import ShopRepository, PrinterRepository
from app.schemas.shop import (
    PrinterResponse,
    PrinterSelectRequest,
    PrinterSelectResponse,
    PrinterSyncRequest,
)
from app.api.deps import require_roles, AuthPrincipal, get_current_user

router = APIRouter()


@router.post("/select", response_model=PrinterSelectResponse)
async def select_printer(
    payload: PrinterSelectRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> PrinterSelectResponse:
    """
    Select an active printer for printing.
    Strictly validates that the printer belongs to the target shop.
    The client cannot select a printer belonging to another shop.
    """
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(payload.shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {payload.shop_id} not found"
        )

    printer_repo = PrinterRepository(db)
    printer = await printer_repo.get_by_id_and_shop(payload.printer_id, payload.shop_id)
    if not printer:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message=f"Printer {payload.printer_id} does not belong to shop {payload.shop_id}"
        )

    # Check printer status
    if printer.status == "OFFLINE":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.PRINTER_OFFLINE,
            message=f"Selected printer {printer.name} is currently OFFLINE"
        )
    elif printer.status == "ERROR":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.HARDWARE_ERROR,
            message=f"Selected printer {printer.name} is in ERROR state"
        )

    return PrinterSelectResponse(
        success=True,
        shop_id=shop.id,
        printer_id=printer.id,
        printer_name=printer.name,
        status=printer.status,
        driver_name=printer.driver_name,
        is_default=printer.is_default,
        supports_color=printer.supports_color,
        supports_duplex=printer.supports_duplex,
        selected_at=datetime.now(timezone.utc)
    )


@router.post("/sync", response_model=List[PrinterResponse])
async def sync_shop_printers(
    payload: PrinterSyncRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> List[PrinterResponse]:
    """
    Syncs discovered local Windows printers reported by Windows Shop Station Agent.
    """
    if principal.shop_id and principal.shop_id != payload.shop_id and principal.role != UserRole.ADMIN:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Cannot sync printers for another shop"
        )

    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(payload.shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {payload.shop_id} not found"
        )

    printer_repo = PrinterRepository(db)
    synced_printers = []

    for prn_data in payload.printers:
        existing = await printer_repo.get_by_id_and_shop(prn_data.id, payload.shop_id)
        if existing:
            existing.name = prn_data.name
            existing.model = prn_data.model or "Generic"
            existing.driver_name = prn_data.driver_name or "NOT_REPORTED"
            existing.connection_info = prn_data.connection_info or "NOT_REPORTED"
            existing.status = prn_data.status
            existing.is_default = prn_data.is_default
            existing.is_online = prn_data.is_online
            existing.supports_color = prn_data.supports_color
            existing.supports_duplex = prn_data.supports_duplex
            existing.supported_paper_sizes = prn_data.supported_paper_sizes or "A4, Letter"
            existing.paper_tray_status = prn_data.paper_tray_status
            existing.toner_level_percent = prn_data.toner_level_percent
            synced_printers.append(existing)
        else:
            new_prn = await printer_repo.create(
                id=prn_data.id,
                shop_id=payload.shop_id,
                name=prn_data.name,
                model=prn_data.model or "Generic",
                driver_name=prn_data.driver_name or "NOT_REPORTED",
                connection_info=prn_data.connection_info or "NOT_REPORTED",
                status=prn_data.status,
                is_default=prn_data.is_default,
                is_online=prn_data.is_online,
                supports_color=prn_data.supports_color,
                supports_duplex=prn_data.supports_duplex,
                supported_paper_sizes=prn_data.supported_paper_sizes or "A4, Letter",
                paper_tray_status=prn_data.paper_tray_status,
                toner_level_percent=prn_data.toner_level_percent
            )
            synced_printers.append(new_prn)

    await db.commit()
    return [PrinterResponse.model_validate(p) for p in synced_printers]
