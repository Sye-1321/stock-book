import { isRecord } from "../../app/api";

export type UnitOfMeasure = { id: string; code: string; name: string };

export type Product = {
  id: string;
  sku: string;
  name: string;
  baseUnitId: string;
  baseUnitCode: string;
  baseUnitName: string;
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
    typeof value.conversionFactor === "string"
  );
}
