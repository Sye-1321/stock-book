import { isRecord } from "../../app/api";

export type UnitOfMeasure = { id: string; code: string; name: string };

export type Product = {
  id: string;
  sku: string;
  name: string;
  baseUnitId: string;
  baseUnitCode: string;
  baseUnitName: string;
  categoryId: string | null;
  categoryName: string | null;
  brandId: string | null;
  brandName: string | null;
  familyId: string | null;
  familyName: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

export type ProductConversion = {
  productId: string;
  alternateUnitId: string;
  alternateUnitCode: string;
  alternateUnitName: string;
  conversionFactor: string;
  active: boolean;
  packagingCompatible: boolean;
  updatedAt: string;
};

export type Classification = {
  id: string;
  code: string;
  name: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

export type ChannelPrice = {
  id: string;
  productId: string;
  unitId: string;
  unitCode: string;
  channel: "WHOLESALE" | "RETAIL";
  amount: string;
  conversionFactor: string | null;
  validFrom: string;
  validTo: string | null;
};

export function isUnit(value: unknown): value is UnitOfMeasure {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string"
  );
}

export function isProduct(value: unknown): value is Product {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.sku === "string" &&
    typeof value.name === "string" &&
    typeof value.baseUnitId === "string" &&
    typeof value.baseUnitCode === "string" &&
    typeof value.baseUnitName === "string" &&
    typeof value.active === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

export function isConversion(value: unknown): value is ProductConversion {
  return (
    isRecord(value) &&
    typeof value.productId === "string" &&
    typeof value.alternateUnitId === "string" &&
    typeof value.alternateUnitCode === "string" &&
    typeof value.alternateUnitName === "string" &&
    typeof value.conversionFactor === "string" &&
    (typeof value.active === "boolean" || value.active === undefined) &&
    (typeof value.packagingCompatible === "boolean" ||
      value.packagingCompatible === undefined) &&
    (typeof value.updatedAt === "string" || value.updatedAt === undefined)
  );
}

export function isClassification(value: unknown): value is Classification {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    typeof value.active === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

export function isChannelPrice(value: unknown): value is ChannelPrice {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.productId === "string" &&
    typeof value.unitId === "string" &&
    typeof value.unitCode === "string" &&
    (value.channel === "WHOLESALE" || value.channel === "RETAIL") &&
    typeof value.amount === "string" &&
    (value.conversionFactor === null ||
      typeof value.conversionFactor === "string") &&
    typeof value.validFrom === "string" &&
    (value.validTo === null || typeof value.validTo === "string")
  );
}
