import { useState } from "react";
import ProductManagement from "./ProductManagement";
import UnitManagement from "./UnitManagement";
import ClassificationManagement from "./ClassificationManagement";

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
};

export default function CatalogManagement({
  isAdmin,
  onSessionExpired,
}: Props) {
  const [unitsRevision, setUnitsRevision] = useState(0);
  const [classificationsRevision, setClassificationsRevision] = useState(0);

  return (
    <>
      <UnitManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        onUnitsChanged={() => setUnitsRevision((current) => current + 1)}
      />
      <ClassificationManagement
        isAdmin={isAdmin}
        onSessionExpired={onSessionExpired}
        onChanged={() => setClassificationsRevision((current) => current + 1)}
      />
      <ProductManagement
        isAdmin={isAdmin}
        unitsRevision={unitsRevision}
        classificationsRevision={classificationsRevision}
        onSessionExpired={onSessionExpired}
      />
    </>
  );
}
