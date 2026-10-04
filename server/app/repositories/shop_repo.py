from typing import List, Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.entities import Shop, Device, Printer
from app.repositories.base import BaseRepository


class ShopRepository(BaseRepository[Shop]):
    def __init__(self, session: AsyncSession):
        super().__init__(Shop, session)

    async def list_verified_shops(self) -> List[Shop]:
        result = await self.session.execute(select(Shop).where(Shop.is_verified == True))
        return list(result.scalars().all())


class DeviceRepository(BaseRepository[Device]):
    def __init__(self, session: AsyncSession):
        super().__init__(Device, session)

    async def get_by_id_and_shop(self, device_id: str, shop_id: str) -> Optional[Device]:
        """
        Enforce device/shop isolation.
        """
        result = await self.session.execute(
            select(Device).where(Device.id == device_id, Device.shop_id == shop_id)
        )
        return result.scalars().first()

    async def list_by_shop(self, shop_id: str) -> List[Device]:
        result = await self.session.execute(
            select(Device).where(Device.shop_id == shop_id)
        )
        return list(result.scalars().all())


class PrinterRepository(BaseRepository[Printer]):
    def __init__(self, session: AsyncSession):
        super().__init__(Printer, session)

    async def get_by_id_and_shop(self, printer_id: str, shop_id: str) -> Optional[Printer]:
        """
        Enforce printer/shop isolation.
        """
        result = await self.session.execute(
            select(Printer).where(Printer.id == printer_id, Printer.shop_id == shop_id)
        )
        return result.scalars().first()

    async def list_by_shop(self, shop_id: str) -> List[Printer]:
        result = await self.session.execute(
            select(Printer).where(Printer.shop_id == shop_id)
        )
        return list(result.scalars().all())
