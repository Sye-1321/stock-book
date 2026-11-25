import { useState } from "react";
import LocationManagement from "../features/locations/LocationManagement";
import StaffManagement from "../features/staff/StaffManagement";
import { User } from "../features/auth/types";
import WarehouseManagement from "../features/warehouses/WarehouseManagement";

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
      {isAdmin && (
        <StaffManagement
          currentUser={user}
          onSessionExpired={onSessionExpired}
        />
      )}
    </>
  );
}
