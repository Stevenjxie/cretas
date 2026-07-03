package com.cretas.aims.service.finance.impl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;

/**
 * 极简 dBASE III (.dbf) 写入器 — 无外部依赖, 手写二进制格式.
 *
 * <p><b>为什么手写</b>: 项目没有 DBF 库依赖 (javadbf 等), 而 dBASE III 文件格式是公开且稳定的二进制布局
 * (32 字节文件头 + 每字段 32 字节描述符 + 0x0D 终止符 + 定长记录 + 0x1A EOF). 金蝶 KIS标准版/迷你版/K3
 * 的原生 "引入" 走该格式的凭证交换文件。
 *
 * <p><b>格式来源</b> (dBASE III/IV/xBase, 公开规范, 多处交叉一致):
 * <ul>
 *   <li>文件头 32 字节: [0]=版本(0x03 dBASE III 无 memo), [1..3]=最后更新 YY(=year-1900)/MM/DD,
 *       [4..7]=记录数(uint32 LE), [8..9]=头长度(uint16 LE), [10..11]=记录长度(uint16 LE),
 *       [29]=语言驱动(LDID); 其余保留 0。</li>
 *   <li>字段描述符 32 字节: [0..10]=字段名(ASCII, 0 结尾/补 0), [11]=类型(C/N/D),
 *       [16]=字段长度, [17]=小数位; 其余保留 0。</li>
 *   <li>记录: 每条以 1 字节删除标记 (0x20=未删) 起头, 之后定长字段值 (C 右补空格, N 右对齐左补空格,
 *       D 存 YYYYMMDD 8 字节)。文件以 0x1A 结尾。</li>
 * </ul>
 *
 * <p><b>编码</b>: 中文字段以 GBK 写入 (金蝶 DBF 交换文件的既定编码), LDID 字节置 0x7A (=code page 936, 简体 GBK),
 * 让金蝶按 GBK 解析。C 字段按 GBK 字节长度定宽, 超长时按字符边界安全截断 (不切半个汉字)。
 */
final class DbfWriter {

    enum Type {
        CHARACTER('C'),
        NUMERIC('N'),
        DATE('D');

        final char code;

        Type(char code) {
            this.code = code;
        }
    }

    /**
     * @param name     字段名 (ASCII, 最长 10 字符)
     * @param type     字段类型
     * @param length   字段字节长度 (C: GBK 字节数; N: 含符号/小数点的总宽; D: 固定 8)
     * @param decimals 小数位 (仅 N 有意义)
     */
    record Field(String name, Type type, int length, int decimals) {
    }

    /** GBK; 金蝶 DBF 交换文件既定编码. 不可用则回落 GB2312 (子集), 再回落平台默认. */
    private static final Charset GBK = resolveGbk();
    private static final byte LDID_GBK_936 = 0x7A;
    private static final byte HEADER_TERMINATOR = 0x0D;
    private static final byte EOF_MARKER = 0x1A;
    private static final byte RECORD_NOT_DELETED = 0x20;

    private DbfWriter() {
    }

    private static Charset resolveGbk() {
        for (String name : new String[]{"GBK", "GB2312", "GB18030"}) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * 写出 DBF 字节.
     *
     * @param fields  字段定义
     * @param records 记录; 每条是与 fields 对齐的字符串值. C 值原文, N 值已格式化的数字字符串 (如 "12.35"),
     *                D 值为 8 位 "YYYYMMDD". null 当空串处理。
     */
    static byte[] write(List<Field> fields, List<List<String>> records) {
        int headerLength = 32 + fields.size() * 32 + 1;
        int recordLength = 1; // deletion flag
        for (Field f : fields) {
            recordLength += f.length();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // ---- File header (32 bytes) ----
        LocalDate today = LocalDate.now();
        out.write(0x03); // dBASE III, no memo
        out.write(today.getYear() - 1900);
        out.write(today.getMonthValue());
        out.write(today.getDayOfMonth());
        writeUInt32LE(out, records.size());
        writeUInt16LE(out, headerLength);
        writeUInt16LE(out, recordLength);
        for (int i = 0; i < 17; i++) {
            out.write(0x00); // reserved [12..28]
        }
        out.write(LDID_GBK_936); // [29] language driver = GBK/936
        out.write(0x00); // [30..31] reserved
        out.write(0x00);

        // ---- Field descriptors (32 bytes each) ----
        for (Field f : fields) {
            byte[] name = new byte[11];
            byte[] nameBytes = f.name().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int n = Math.min(nameBytes.length, 10); // leave a trailing 0
            System.arraycopy(nameBytes, 0, name, 0, n);
            out.write(name, 0, 11);
            out.write(f.type().code);
            writeUInt32LE(out, 0); // field data address (reserved)
            out.write(f.length() & 0xFF);
            out.write(f.decimals() & 0xFF);
            for (int i = 0; i < 14; i++) {
                out.write(0x00); // reserved [18..31]
            }
        }
        out.write(HEADER_TERMINATOR);

        // ---- Records ----
        for (List<String> record : records) {
            out.write(RECORD_NOT_DELETED);
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                String value = i < record.size() && record.get(i) != null ? record.get(i) : "";
                writeFieldValue(out, f, value);
            }
        }
        out.write(EOF_MARKER);

        return out.toByteArray();
    }

    private static void writeFieldValue(ByteArrayOutputStream out, Field f, String value) {
        byte[] cell = new byte[f.length()];
        switch (f.type()) {
            case CHARACTER -> {
                byte[] encoded = safeEncode(value, f.length());
                java.util.Arrays.fill(cell, (byte) 0x20); // space pad
                System.arraycopy(encoded, 0, cell, 0, encoded.length); // left-justified
            }
            case NUMERIC -> {
                // right-justified, space padded left; overflow => stars (dBASE convention)
                byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                java.util.Arrays.fill(cell, (byte) 0x20);
                if (encoded.length > f.length()) {
                    java.util.Arrays.fill(cell, (byte) '*');
                } else {
                    System.arraycopy(encoded, 0, cell, f.length() - encoded.length, encoded.length);
                }
            }
            case DATE -> {
                byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                java.util.Arrays.fill(cell, (byte) 0x20);
                int len = Math.min(encoded.length, f.length());
                System.arraycopy(encoded, 0, cell, 0, len);
            }
        }
        out.write(cell, 0, cell.length);
    }

    /** GBK-encode {@code value}, truncating on a character boundary so no half 汉字 is emitted. */
    private static byte[] safeEncode(String value, int maxBytes) {
        byte[] full = value.getBytes(GBK);
        if (full.length <= maxBytes) {
            return full;
        }
        // Truncate on char boundary: encode progressively longer prefixes until byte cap.
        int chars = value.length();
        while (chars > 0) {
            byte[] candidate = value.substring(0, chars).getBytes(GBK);
            if (candidate.length <= maxBytes) {
                return candidate;
            }
            chars--;
        }
        return new byte[0];
    }

    private static void writeUInt16LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
