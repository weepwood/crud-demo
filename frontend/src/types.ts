export interface SchemaInfo {
  name: string
}

export interface TableInfo {
  schema: string
  name: string
  estimatedRows: number
  hasPrimaryKey: boolean
  canInsert: boolean
  canUpdate: boolean
  canDelete: boolean
}

export interface ColumnInfo {
  name: string
  formattedType: string
  udtName: string
  typeCategory: string
  nullable: boolean
  defaultValue: string | null
  identity: boolean
  generated: boolean
  primaryKey: boolean
  ordinalPosition: number
}

export interface TableMetadata {
  schema: string
  table: string
  columns: ColumnInfo[]
  primaryKeys: string[]
  canInsert: boolean
  canUpdate: boolean
  canDelete: boolean
}

export interface PageResponse {
  rows: Record<string, unknown>[]
  page: number
  size: number
  total: number
}

export interface MutationRequest {
  values?: Record<string, unknown>
  keys?: Record<string, unknown>
  rowId?: string
}
