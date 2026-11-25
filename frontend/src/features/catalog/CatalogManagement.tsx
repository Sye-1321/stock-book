import { useState } from "react";
import ProductManagement from "./ProductManagement";
import UnitManagement from "./UnitManagement";

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
};

export default function CatalogManagement({
  isAdmin,
  onSessionExpired,
}: Props) {
  const [unitsRevision, setUnitsRevision] = useState(0);

  return (
    <>
      <UnitManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        onUnitsChanged={() => setUnitsRevision((current) => current + 1)}
      />
      <ProductManagement
        isAdmin={isAdmin}
        unitsRevision={unitsRevision}
        onSessionExpired={onSessionExpired}
      />
    </>
  );
}
