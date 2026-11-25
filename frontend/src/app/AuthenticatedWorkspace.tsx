import { useState } from "react";
import CatalogManagement from "../features/catalog/CatalogManagement";
import LocationManagement from "../features/locations/LocationManagement";
import StaffManagement from "../features/staff/StaffManagement";
import { User } from "../features/auth/types";
import WarehouseManagement from "../features/warehouses/WarehouseManagement";
import InventoryManagement from "../features/inventory/InventoryManagement";

type Props = {
  user: User;
  onSessionExpired: (message?: string) => void;
};

export default function AuthenticatedWorkspace({
  user,
  onSessionExpired,
}: Props) {
  const [locationsRevision, setLocationsRevision] = useState(0);
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
      />
      {isAdmin && (
        <StaffManagement
          currentUser={user}
          onSessionExpired={onSessionExpired}
        />
      )}
    </>
  );
}
