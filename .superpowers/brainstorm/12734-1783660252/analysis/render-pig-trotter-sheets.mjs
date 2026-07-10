import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const outputDir = "C:/Users/Steve/my-prototype-logistics/.superpowers/brainstorm/12734-1783660252/analysis/previews";
await fs.mkdir(outputDir, { recursive: true });

const sources = [
  {
    key: "five",
    file: "B:/Download-Chrome/五香去骨猪蹄-订单明细(2).xlsx",
    sheets: ["前处理 领料-分切-化冻捞出-焯水油炸", "熟制", "剔骨", "辅料成本"],
  },
  {
    key: "thai",
    file: "B:/Download-Chrome/泰式酸辣猪蹄v1.0.xlsx",
    sheets: ["分切烧毛焯水", "熟制剔骨", "泡制", "辅料成本"],
  },
];

for (const source of sources) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source.file));
  for (let index = 0; index < source.sheets.length; index += 1) {
    const sheetName = source.sheets[index];
    const preview = await workbook.render({ sheetName, autoCrop: "all", scale: 0.8, format: "png" });
    const safeName = `${source.key}-${index + 1}.png`;
    await fs.writeFile(path.join(outputDir, safeName), new Uint8Array(await preview.arrayBuffer()));
    console.log(`${safeName}\t${sheetName}`);
  }
}
