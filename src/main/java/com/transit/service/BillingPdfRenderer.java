package com.transit.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Renders the invoice and receipt with the same one-page Letter layout as the supplied references. */
final class BillingPdfRenderer {

    private static final Color BLACK = new Color(17, 17, 17);
    private static final Color LIGHT_LINE = new Color(218, 218, 218);
    private static final Color LINK_BLUE = new Color(99, 91, 255);
    private static final List<String> SYSTEM_FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyh.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf"
    );

    private final String configuredFontPath;

    BillingPdfRenderer(String configuredFontPath) {
        this.configuredFontPath = configuredFontPath;
    }

    byte[] renderInvoice(DocumentData data) {
        return render(data, false);
    }

    byte[] renderReceipt(DocumentData data) {
        return render(data, true);
    }

    private byte[] render(DocumentData data, boolean receipt) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            FontSet fonts = loadFonts(document, data);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(BLACK);
                drawHeader(content, fonts, data, receipt);
                drawParties(content, fonts, data);
                if (receipt) {
                    drawReceiptBody(content, fonts, data);
                } else {
                    drawInvoiceBody(content, fonts, data);
                }
                drawFooter(content, fonts);
            }
            document.getDocumentInformation().setTitle(receipt ? "Receipt" : "Invoice");
            document.getDocumentInformation().setAuthor(data.merchantName());
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to generate billing PDF", exception);
        }
    }

    private void drawHeader(PDPageContentStream content, FontSet fonts, DocumentData data, boolean receipt)
            throws IOException {
        text(content, fonts.bold(), receipt ? "Receipt" : "Invoice", 30, 746, 27);
        drawMerchantMark(content, 558, 742);

        float labelX = 30;
        float valueX = 104;
        text(content, fonts.bold(), "Invoice number", labelX, 711, 9.5f);
        fittedText(content, fonts.bold(), data.invoiceNumber(), valueX, 711, 9.5f, 150);
        if (receipt) {
            text(content, fonts.bold(), "Receipt number", labelX, 697, 9.5f);
            text(content, fonts.regular(), data.receiptNumber(), valueX, 697, 9.5f);
            text(content, fonts.bold(), "Date paid", labelX, 683, 9.5f);
            text(content, fonts.regular(), data.documentDate(), valueX, 683, 9.5f);
        } else {
            text(content, fonts.bold(), "Date of issue", labelX, 697, 9.5f);
            text(content, fonts.regular(), data.documentDate(), valueX, 697, 9.5f);
            text(content, fonts.bold(), "Date due", labelX, 683, 9.5f);
            text(content, fonts.regular(), data.documentDate(), valueX, 683, 9.5f);
        }
    }

    private void drawParties(PDPageContentStream content, FontSet fonts, DocumentData data) throws IOException {
        text(content, fonts.bold(), data.merchantName(), 30, 641, 10);
        text(content, fonts.regular(), data.merchantAddressLine1(), 30, 625, 9.5f);
        text(content, fonts.regular(), data.merchantAddressLine2(), 30, 609, 9.5f);
        text(content, fonts.regular(), data.merchantCountry(), 30, 593, 9.5f);
        text(content, fonts.regular(), data.merchantEmail(), 30, 577, 9.5f);

        float x = 250;
        text(content, fonts.bold(), "Bill to", x, 641, 10);
        fittedText(content, fontFor(fonts, data.billingName()), data.billingName(), x, 625, 9.5f, 330);
        fittedText(content, fontFor(fonts, data.billingAddressLine1()), data.billingAddressLine1(), x, 609, 9.5f, 330);
        String districtAndCity = data.billingDistrict() + ", " + data.billingCity();
        fittedText(content, fontFor(fonts, districtAndCity), districtAndCity, x, 593, 9.5f, 330);
        String provinceAndPostalCode = data.billingProvince() + " " + data.billingPostalCode();
        fittedText(content, fontFor(fonts, provinceAndPostalCode), provinceAndPostalCode, x, 577, 9.5f, 330);
        fittedText(content, fontFor(fonts, data.billingCountry()), data.billingCountry(), x, 561, 9.5f, 330);
        fittedText(content, fonts.regular(), data.billingEmail(), x, 545, 9.5f, 330);
    }

    private void drawInvoiceBody(PDPageContentStream content, FontSet fonts, DocumentData data) throws IOException {
        text(content, fonts.bold(), data.amount() + " due " + data.documentDate(), 30, 502, 15.5f);
        text(content, fonts.bold(), "Pay online", 30, 479, 9.5f, LINK_BLUE);
        line(content, 30, 477, 77, 477, LINK_BLUE, 0.8f);
        drawItemsTable(content, fonts, data, 448, false);
    }

    private void drawReceiptBody(PDPageContentStream content, FontSet fonts, DocumentData data) throws IOException {
        text(content, fonts.bold(), data.amount() + " paid on " + data.documentDate(), 30, 502, 15.5f);
        drawItemsTable(content, fonts, data, 461, true);

        text(content, fonts.bold(), "Payment history", 30, 315, 15.5f);
        text(content, fonts.regular(), "Payment method", 30, 279, 8);
        text(content, fonts.regular(), "Date", 316, 279, 8);
        text(content, fonts.regular(), "Amount paid", 414, 279, 8);
        rightText(content, fonts.regular(), "Receipt number", 582, 279, 8);
        line(content, 30, 270, 582, 270, BLACK, 0.85f);
        text(content, fonts.regular(), data.paymentMethod(), 30, 253, 9.5f);
        text(content, fonts.regular(), data.documentDate(), 316, 253, 9.5f);
        text(content, fonts.regular(), data.amount(), 414, 253, 9.5f);
        rightText(content, fonts.regular(), data.receiptNumber(), 582, 253, 9.5f);
    }

    private void drawItemsTable(PDPageContentStream content,
                                FontSet fonts,
                                DocumentData data,
                                float headerY,
                                boolean receipt) throws IOException {
        text(content, fonts.regular(), "Description", 30, headerY, 8);
        rightText(content, fonts.regular(), "Qty", 450, headerY, 8);
        rightText(content, fonts.regular(), "Unit price", 515, headerY, 8);
        rightText(content, fonts.regular(), "Amount", 582, headerY, 8);
        line(content, 30, headerY - 9, 582, headerY - 9, BLACK, 0.85f);

        fittedText(content, fontFor(fonts, data.description()), data.description(), 30, headerY - 29, 9.5f, 370);
        rightText(content, fonts.regular(), "1", 450, headerY - 29, 9.5f);
        rightText(content, fonts.regular(), data.amount(), 515, headerY - 29, 9.5f);
        rightText(content, fonts.regular(), data.amount(), 582, headerY - 29, 9.5f);

        boolean hasBonus = data.bonusDescription() != null && !data.bonusDescription().isBlank();
        if (hasBonus) {
            fittedText(content, fontFor(fonts, data.bonusDescription()), data.bonusDescription(), 30, headerY - 47, 9.5f, 370);
            rightText(content, fonts.regular(), "1", 450, headerY - 47, 9.5f);
            rightText(content, fonts.regular(), data.bonusAmount(), 515, headerY - 47, 9.5f);
            rightText(content, fonts.regular(), data.bonusAmount(), 582, headerY - 47, 9.5f);
        }

        float totalsTop = headerY - (hasBonus ? 81 : 63);
        line(content, 306, totalsTop, 582, totalsTop, LIGHT_LINE, 0.65f);
        text(content, fonts.regular(), "Subtotal", 306, totalsTop - 14, 9.5f);
        rightText(content, fonts.regular(), data.amount(), 582, totalsTop - 14, 9.5f);
        line(content, 306, totalsTop - 19, 582, totalsTop - 19, LIGHT_LINE, 0.65f);
        text(content, fonts.regular(), "Total", 306, totalsTop - 33, 9.5f);
        rightText(content, fonts.regular(), data.amount(), 582, totalsTop - 33, 9.5f);
        line(content, 306, totalsTop - 38, 582, totalsTop - 38, LIGHT_LINE, 0.65f);
        text(content, fonts.bold(), receipt ? "Amount paid" : "Amount due", 306, totalsTop - 52, 9.5f);
        rightText(content, fonts.bold(), data.amount(), 582, totalsTop - 52, 9.5f);
    }

    private void drawFooter(PDPageContentStream content, FontSet fonts) throws IOException {
        line(content, 30, 42, 582, 42, LIGHT_LINE, 0.65f);
        rightText(content, fonts.regular(), "Page 1 of 1", 582, 20, 8);
    }

    private FontSet loadFonts(PDDocument document, DocumentData data) throws IOException {
        Path fontPath = resolveFontPath();
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        if (fontPath != null) {
            PDFont embedded = PDType0Font.load(document, fontPath.toFile());
            return new FontSet(regular, bold, embedded);
        }
        if (containsNonAscii(data.allText())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A CJK TrueType font is required; configure payment.documents.font-path");
        }
        return new FontSet(regular, bold, regular);
    }

    private PDFont fontFor(FontSet fonts, String value) {
        return containsNonAscii(value) ? fonts.cjk() : fonts.regular();
    }

    private Path resolveFontPath() {
        List<String> candidates = new ArrayList<>();
        if (configuredFontPath != null && !configuredFontPath.isBlank()) {
            candidates.add(configuredFontPath.trim());
        }
        candidates.addAll(SYSTEM_FONT_CANDIDATES);
        return candidates.stream()
                .map(Path::of)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private boolean containsNonAscii(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> codePoint > 0x7e);
    }

    private void drawMerchantMark(PDPageContentStream content, float centerX, float centerY) throws IOException {
        content.setStrokingColor(BLACK);
        content.setLineWidth(1.8f);
        circle(content, centerX, centerY + 5, 10);
        circle(content, centerX + 6, centerY - 3, 10);
        circle(content, centerX - 6, centerY - 3, 10);
        circle(content, centerX, centerY, 5);
        content.stroke();
    }

    private void circle(PDPageContentStream content, float x, float y, float radius) throws IOException {
        float k = 0.55228475f * radius;
        content.moveTo(x + radius, y);
        content.curveTo(x + radius, y + k, x + k, y + radius, x, y + radius);
        content.curveTo(x - k, y + radius, x - radius, y + k, x - radius, y);
        content.curveTo(x - radius, y - k, x - k, y - radius, x, y - radius);
        content.curveTo(x + k, y - radius, x + radius, y - k, x + radius, y);
    }

    private void fittedText(PDPageContentStream content,
                            PDFont font,
                            String value,
                            float x,
                            float y,
                            float size,
                            float maxWidth) throws IOException {
        float fitted = size;
        while (fitted > 6.5f && width(font, value, fitted) > maxWidth) fitted -= 0.5f;
        text(content, font, value, x, y, fitted);
    }

    private void rightText(PDPageContentStream content,
                           PDFont font,
                           String value,
                           float rightX,
                           float y,
                           float size) throws IOException {
        text(content, font, value, rightX - width(font, value, size), y, size);
    }

    private float width(PDFont font, String value, float size) throws IOException {
        return font.getStringWidth(value == null ? "" : value) / 1000f * size;
    }

    private void text(PDPageContentStream content,
                      PDFont font,
                      String value,
                      float x,
                      float y,
                      float size) throws IOException {
        text(content, font, value, x, y, size, BLACK);
    }

    private void text(PDPageContentStream content,
                      PDFont font,
                      String value,
                      float x,
                      float y,
                      float size,
                      Color color) throws IOException {
        content.beginText();
        content.setNonStrokingColor(color);
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value == null ? "" : value);
        content.endText();
        content.setNonStrokingColor(BLACK);
    }

    private void line(PDPageContentStream content,
                      float x1,
                      float y1,
                      float x2,
                      float y2,
                      Color color,
                      float width) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(width);
        content.moveTo(x1, y1);
        content.lineTo(x2, y2);
        content.stroke();
        content.setStrokingColor(BLACK);
    }

    record DocumentData(String invoiceNumber,
                        String receiptNumber,
                        String documentDate,
                        String merchantName,
                        String merchantAddressLine1,
                        String merchantAddressLine2,
                        String merchantCountry,
                        String merchantEmail,
                        String billingName,
                        String billingAddressLine1,
                        String billingDistrict,
                        String billingCity,
                        String billingProvince,
                        String billingPostalCode,
                        String billingCountry,
                        String billingEmail,
                        String description,
                        String amount,
                        String paymentMethod,
                        String bonusDescription,
                        String bonusAmount) {
        DocumentData(String invoiceNumber,String receiptNumber,String documentDate,String merchantName,String merchantAddressLine1,
                     String merchantAddressLine2,String merchantCountry,String merchantEmail,String billingName,String billingAddressLine1,
                     String billingDistrict,String billingCity,String billingProvince,String billingPostalCode,String billingCountry,String billingEmail,
                     String description,String amount,String paymentMethod) {
            this(invoiceNumber,receiptNumber,documentDate,merchantName,merchantAddressLine1,merchantAddressLine2,merchantCountry,merchantEmail,
                    billingName,billingAddressLine1,billingDistrict,billingCity,billingProvince,billingPostalCode,billingCountry,billingEmail,
                    description,amount,paymentMethod,"","");
        }
        String allText() {
            return String.join("", invoiceNumber, receiptNumber, documentDate, merchantName,
                    merchantAddressLine1, merchantAddressLine2, merchantCountry, merchantEmail,
                    billingName, billingAddressLine1, billingDistrict, billingCity, billingProvince,
                    billingPostalCode, billingCountry, billingEmail, description, amount, paymentMethod,
                    bonusDescription == null ? "" : bonusDescription, bonusAmount == null ? "" : bonusAmount);
        }
    }

    private record FontSet(PDFont regular, PDFont bold, PDFont cjk) {
    }
}
