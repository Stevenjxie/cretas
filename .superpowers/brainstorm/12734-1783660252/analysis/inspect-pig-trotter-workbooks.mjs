import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const [path, sheetName, range] = process.argv.slice(2);
if (!path || !sheetName || !range) {
  throw new Error("Usage: node inspect-pig-trotter-workbooks.mjs <path> <sheet> <range>");
}

const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(path));
const result = await workbook.inspect({
  kind: "table",
  sheetId: sheetName,
  range,
  include: "values,formulas",
  maxChars: 30000,
  tableMaxRows: 60,
  tableMaxCols: 36,
  tableMaxCellChars: 180,
});

console.log(`=== ${path} :: ${sheetName}!${range} ===`);
console.log(result.ndjson);
