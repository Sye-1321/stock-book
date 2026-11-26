import { useState } from "react";
import CatalogManagement from "../features/catalog/CatalogManagement";
import LocationManagement from "../features/locations/LocationManagement";
import StaffManagement from "../features/staff/StaffManagement";
import { User } from "../features/auth/types";
import WarehouseManagement from "../features/warehouses/WarehouseManagement";
import InventoryManagement from "../features/inventory/InventoryManagement";
import SupplierManagement from "../features/suppliers/SupplierManagement";
import GoodsReceiving from "../features/receiving/GoodsReceiving";

type Props = {
  user: User;
  onSessionExpired: (message?: string) => void;
};

export default function AuthenticatedWorkspace({
  user,
  onSessionExpired,
}: Props) {
  const [locationsRevision, setLocationsRevision] = useState(0);
  const [suppliersRevision, setSuppliersRevision] = useState(0);
  const [inventoryRevision, setInventoryRevision] = useState(0);
  const isAdmin = user.role === "ADMIN";

  return (
    <>
      <CatalogManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
      />
      <LocationManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        onLocationsChanged={() =>
          setLocationsRevision((current) => current + 1)
        }
      />
      <WarehouseManagement
        isAdmin={isAdmin}
        locationsRevision={locationsRevision}
        onSessionExpired={onSessionExpired}
      />
      <InventoryManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        revision={inventoryRevision}
      />
      <SupplierManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        onSuppliersChanged={() =>
          setSuppliersRevision((current) => current + 1)
        }
      />
      {isAdmin && (
        <GoodsReceiving
          suppliersRevision={suppliersRevision}
          onSessionExpired={onSessionExpired}
          onReceiptPosted={() => setInventoryRevision((current) => current + 1)}
        />
      )}
      {isAdmin && (
        <StaffManagement
          currentUser={user}
          onSessionExpired={onSessionExpired}
        />
      )}
    </>
  );
}
