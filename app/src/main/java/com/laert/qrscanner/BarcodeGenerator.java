package com.laert.qrscanner;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

final class BarcodeGenerator {

    enum Format {
        QR("QR Code", BarcodeFormat.QR_CODE, true, false, 1024, 1024, "Enter text"),
        CODE_128("Code 128", BarcodeFormat.CODE_128, false, false, 1000, 300,
                "Any text (letters, digits, symbols)"),
        CODE_39("Code 39", BarcodeFormat.CODE_39, false, false, 1000, 300,
                "Letters, digits and - . $ / + %"),
        EAN_13("EAN-13", BarcodeFormat.EAN_13, false, true, 1000, 300,
                "12 digits (check digit added) or 13"),
        EAN_8("EAN-8", BarcodeFormat.EAN_8, false, true, 1000, 300,
                "7 digits (check digit added) or 8"),
        UPC_A("UPC-A", BarcodeFormat.UPC_A, false, true, 1000, 300,
                "11 digits (check digit added) or 12"),
        UPC_E("UPC-E", BarcodeFormat.UPC_E, false, true, 1000, 300,
                "7 or 8 digits, starting with 0 or 1"),
        ITF("ITF (Interleaved 2 of 5)", BarcodeFormat.ITF, false, true, 1000, 300,
                "An even number of digits"),
        CODABAR("Codabar", BarcodeFormat.CODABAR, false, false, 1000, 300,
                "Digits and - $ : / . + (e.g. A123456B)"),
        PDF_417("PDF417", BarcodeFormat.PDF_417, true, false, 1200, 400, "Enter text"),
        AZTEC("Aztec", BarcodeFormat.AZTEC, true, false, 768, 768, "Enter text"),
        DATA_MATRIX("Data Matrix", BarcodeFormat.DATA_MATRIX, true, false, 768, 768, "Enter text");

        final String label;
        final BarcodeFormat zxing;
        final boolean supportsContentTypes;
        final boolean numericOnly;
        final int width;
        final int height;
        final String hint;

        Format(String label, BarcodeFormat zxing, boolean supportsContentTypes,
               boolean numericOnly, int width, int height, String hint) {
            this.label = label;
            this.zxing = zxing;
            this.supportsContentTypes = supportsContentTypes;
            this.numericOnly = numericOnly;
            this.width = width;
            this.height = height;
            this.hint = hint;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Problem {
        EMPTY, DIGITS_ONLY, DIGIT_COUNT, UPCE_START, ITF_EVEN, TOO_LONG, ASCII_ONLY,
        CHECK_DIGIT, UNSUPPORTED_CHARS, TOO_MUCH, CANNOT_CREATE
    }

    static final class CodeException extends IllegalArgumentException {
        final Problem problem;
        final Object[] args;

        CodeException(Problem problem, String englishMessage, Object... args) {
            super(englishMessage);
            this.problem = problem;
            this.args = args;
        }
    }

    private BarcodeGenerator() {
    }

    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    static CodeException validate(Format f, String content) {
        if (content == null || content.isEmpty()) {
            return new CodeException(Problem.EMPTY, "Please enter content");
        }
        switch (f) {
            case EAN_13:
                return digitsOfLength(content, f, 12, 13);
            case EAN_8:
                return digitsOfLength(content, f, 7, 8);
            case UPC_A:
                return digitsOfLength(content, f, 11, 12);
            case UPC_E: {
                CodeException p = digitsOfLength(content, f, 7, 8);
                if (p != null) return p;
                if (content.charAt(0) != '0' && content.charAt(0) != '1') {
                    return new CodeException(Problem.UPCE_START, "UPC-E must start with 0 or 1");
                }
                return null;
            }
            case ITF:
                if (!DIGITS.matcher(content).matches()) {
                    return new CodeException(Problem.DIGITS_ONLY, "ITF accepts digits only", f.label);
                }
                if (content.length() % 2 != 0) {
                    return new CodeException(Problem.ITF_EVEN, "ITF needs an even number of digits");
                }
                if (content.length() > 80) return tooLong(f, 80);
                return null;
            case CODE_128:
                for (int i = 0; i < content.length(); i++) {
                    if (content.charAt(i) > 127) {
                        return new CodeException(Problem.ASCII_ONLY,
                                "Code 128 supports ASCII characters only", f.label);
                    }
                }
                if (content.length() > 80) return tooLong(f, 80);
                return null;
            case CODE_39:
            case CODABAR:
                if (content.length() > 80) return tooLong(f, 80);
                return null;
            default:
                return null;
        }
    }

    private static CodeException tooLong(Format f, int max) {
        return new CodeException(Problem.TOO_LONG,
                f.label + " is limited to " + max + " characters here", f.label, max);
    }

    private static CodeException digitsOfLength(String content, Format f, int a, int b) {
        if (!DIGITS.matcher(content).matches()) {
            return new CodeException(Problem.DIGITS_ONLY, f.label + " accepts digits only", f.label);
        }
        if (content.length() != a && content.length() != b) {
            return new CodeException(Problem.DIGIT_COUNT,
                    f.label + " needs " + a + " or " + b + " digits (you entered " + content.length() + ")",
                    f.label, a, b, content.length());
        }
        return null;
    }

    private static BitMatrix withQuietZone(BitMatrix src, int pad) {
        BitMatrix out = new BitMatrix(src.getWidth() + 2 * pad, src.getHeight() + 2 * pad);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if (src.get(x, y)) out.set(x + pad, y + pad);
            }
        }
        return out;
    }

    static BitMatrix encode(Format f, String content) {
        CodeException problem = validate(f, content);
        if (problem != null) throw problem;

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        if (f.supportsContentTypes && !StandardCharsets.ISO_8859_1.newEncoder().canEncode(content)) {
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        }
        if (f == Format.DATA_MATRIX) {
            hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
        }
        if (!f.supportsContentTypes) {
            hints.put(EncodeHintType.MARGIN, 20);
        }
        try {
            BitMatrix m = new MultiFormatWriter().encode(content, f.zxing, f.width, f.height, hints);
            if (f == Format.DATA_MATRIX || f == Format.AZTEC) {
                m = withQuietZone(m, 64);
            }
            return m;
        } catch (WriterException e) {
            throw new CodeException(Problem.TOO_MUCH, "Too much content for this code type");
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("checksum")) {
                throw new CodeException(Problem.CHECK_DIGIT, f.label + ": the check digit is wrong", f.label);
            }
            if (m.contains("bad character") || m.contains("illegal") || m.contains("invalid")
                    || m.contains("cannot encode")) {
                throw new CodeException(Problem.UNSUPPORTED_CHARS,
                        f.label + " does not support some of these characters", f.label);
            }
            throw new CodeException(Problem.CANNOT_CREATE, "Cannot create " + f.label + " from this content", f.label);
        }
    }
}
