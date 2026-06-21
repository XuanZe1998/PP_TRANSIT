package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.PlusOrderRequest;
import com.transit.dto.PlusOrderResponse;
import com.transit.mapper.PlusOrderMapper;
import com.transit.mapper.PlusProductMapper;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlusOrderService {

    private final PlusProductMapper productMapper;
    private final PlusOrderMapper orderMapper;
    private Double cachedUsdRate;
    private LocalDate cachedUsdRateDate;

    public List<PlusProduct> listEnabledProducts() {
        return productMapper.selectList(new LambdaQueryWrapper<PlusProduct>()
                .orderByDesc(PlusProduct::getCreatedAt));
    }

    public List<PlusProduct> listAllProducts() {
        return productMapper.selectList(new LambdaQueryWrapper<PlusProduct>()
                .orderByDesc(PlusProduct::getCreatedAt));
    }

    public PlusProduct createProduct(PlusProduct request) {
        PlusProduct product = PlusProduct.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .priceCents(request.getPriceCents() == null ? 0 : request.getPriceCents())
                .serviceFeeCents(request.getServiceFeeCents() == null ? 0 : request.getServiceFeeCents())
                .createdAt(request.getCreatedAt() == null ? LocalDateTime.now() : request.getCreatedAt())
                .build();
        productMapper.insert(product);
        return product;
    }

    public PlusProduct updateProduct(Long id, PlusProduct request) {
        PlusProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setImageUrl(request.getImageUrl());
        product.setPriceCents(request.getPriceCents() == null ? 0 : request.getPriceCents());
        product.setServiceFeeCents(request.getServiceFeeCents() == null ? 0 : request.getServiceFeeCents());
        if (request.getCreatedAt() != null) {
            product.setCreatedAt(request.getCreatedAt());
        }
        productMapper.updateById(product);
        return product;
    }

    public void deleteProduct(Long id) {
        productMapper.deleteById(id);
    }

    public PlusOrderResponse createOrder(User user, PlusOrderRequest request) {
        if (request.getProductId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId is required");
        }
        PlusProduct product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }

        long unitPriceCents = product.getPriceCents() == null ? 0 : product.getPriceCents();
        long serviceFeeCents = product.getServiceFeeCents() == null ? 0 : product.getServiceFeeCents();
        PlusOrder order = PlusOrder.builder()
                .orderNo(generateOrderNo())
                .userId(user.getId())
                .productId(product.getId())
                .productName(product.getName())
                .unitPriceCents(unitPriceCents)
                .serviceFeeCents(serviceFeeCents)
                .amountCents(unitPriceCents + serviceFeeCents)
                .status("PENDING")
                .fulfillmentNote("Order created. Waiting for admin confirmation and fulfillment.")
                .createdAt(LocalDateTime.now())
                .build();
        orderMapper.insert(order);

        return PlusOrderResponse.builder()
                .order(order)
                .message("订单已创建")
                .build();
    }

    public List<PlusOrder> listUserOrders(User user) {
        return orderMapper.selectList(new LambdaQueryWrapper<PlusOrder>()
                .eq(PlusOrder::getUserId, user.getId())
                .orderByDesc(PlusOrder::getCreatedAt));
    }

    public List<PlusOrder> listAllOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<PlusOrder>().orderByDesc(PlusOrder::getCreatedAt));
    }

    public PlusOrder getUserOrder(User user, Long id) {
        PlusOrder order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return order;
    }

    public PlusOrder fulfillOrder(Long id, String status, String fulfillmentNote) {
        PlusOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        if (status != null && !status.isBlank()) {
            order.setStatus(status);
        }
        if (fulfillmentNote != null) {
            order.setFulfillmentNote(fulfillmentNote);
        }
        orderMapper.updateById(order);
        return order;
    }

    public void deleteOrder(Long id) {
        PlusOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        orderMapper.deleteById(id);
    }

    public byte[] buildDownload(User user, Long id) {
        PlusOrder order = getUserOrder(user, id);
        order.setDownloadedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        return buildReceiptPdf(user, order);
    }

    private String generateOrderNo() {
        return "PLUS" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private byte[] buildReceiptPdf(User user, PlusOrder order) {
        String receiptNumber = randomReceiptNumber();
        ZonedDateTime paidAt = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        String paidDate = formatReceiptDate(paidAt);
        long unitPriceCents = receiptUnitPriceCents(order);
        long serviceFeeCents = receiptServiceFeeCents(order);
        String unitPrice = formatUsd(unitPriceCents);
        String fee = formatUsd(serviceFeeCents);
        String amount = formatUsd(unitPriceCents + serviceFeeCents);
        double usdRate = getUSDRate();
        String cnyAmount = formatCny(unitPriceCents + serviceFeeCents, usdRate);
        String rateText = String.format(Locale.US, "USD/CNY rate %.4f", usdRate);
        String buyerName = ascii(safe(user.getUsername()));
        String buyerEmail = ascii(user.getEmail() == null || user.getEmail().isBlank() ? "customer@example.com" : user.getEmail());
        String productName = ascii(order.getProductName() == null || order.getProductName().isBlank()
                ? "ChatGPT Plus"
                : order.getProductName());

        PdfCanvas canvas = new PdfCanvas();
        canvas.text("Receipt", 54, 792, 26, true);

        canvas.text("Receipt number", 54, 736, 9, false);
        canvas.text(receiptNumber, 154, 736, 9, false);
        canvas.text("Date paid", 54, 720, 9, false);
        canvas.text(paidDate, 154, 720, 9, false);

        canvas.text("start.ai", 54, 660, 10, true);
        canvas.text("Star Tech Limited", 54, 642, 9, false);
        canvas.text("614 E 85TH ST FL2", 54, 628, 9, false);
        canvas.text("BROOKLYN, NY 11236", 54, 614, 9, false);
        canvas.text("bqrlgm69987s@hotmail.com", 54, 600, 9, false);

        canvas.text("Bill to", 334, 660, 10, true);
        canvas.text(buyerName, 334, 642, 9, false);
        canvas.text(buyerEmail, 334, 628, 9, false);

        canvas.text(amount + " USD paid on " + paidDate, 54, 526, 20, true);

        canvas.line(54, 474, 542, 474);
        canvas.text("Date", 54, 456, 9, true);
        canvas.text("Description", 132, 456, 9, true);
        canvas.text("Qty", 324, 456, 9, true);
        canvas.text("Unit price", 368, 456, 9, true);
        canvas.text("Fee", 452, 456, 9, true);
        canvas.text("Amount", 506, 456, 9, true);
        canvas.line(54, 444, 542, 444);

        canvas.text(paidDate, 54, 424, 9, false);
        canvas.text(productName, 132, 424, 9, false);
        canvas.text("1", 328, 424, 9, false);
        canvas.text(unitPrice, 368, 424, 9, false);
        canvas.text(fee, 452, 424, 9, false);
        canvas.text(amount, 506, 424, 9, false);
        canvas.line(54, 394, 542, 394);

        canvas.text("Subtotal", 392, 366, 9, false);
        canvas.text(amount, 502, 366, 9, false);
        canvas.text("Total", 392, 340, 10, true);
        canvas.text(amount, 502, 340, 10, true);
        canvas.line(392, 326, 542, 326);
        canvas.text("Amount paid", 392, 304, 9, true);
        canvas.text(amount + " USD", 502, 304, 9, true);
        canvas.text("Settled in CNY", 392, 282, 9, false);
        canvas.text(cnyAmount, 502, 282, 9, false);
        canvas.text(rateText, 392, 264, 9, false);

        canvas.text(receiptNumber + " - " + amount + " Page 1 of 1", 54, 36, 9, false);
        return canvas.toPdf();
    }


    //获取美元汇率
    private synchronized Double getUSDRate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (cachedUsdRate != null && today.equals(cachedUsdRateDate)) {
            return cachedUsdRate;
        }
        try {
            URL url = new URL("https://v6.exchangerate-api.com/v6/d437fdf27cab7d1fad098776/latest/USD");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            StringBuilder response = new StringBuilder();
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            // 解析JSON获取CNY汇率
            String json = response.toString();
            double rate = Double.parseDouble(json.split("\"CNY\":")[1].split(",")[0]);
            cachedUsdRate = rate;
            cachedUsdRateDate = today;
            return rate;
        } catch (Exception e) {
            if (cachedUsdRate != null) {
                return cachedUsdRate;
            }
        }

        return 6.78;
    }

    private String formatUsd(Long cents) {
        return "$" + String.format(Locale.US, "%.2f", (cents == null ? 0 : cents) / 100.0);
    }

    private String formatCny(long usdCents, double usdRate) {
        return "CNY " + String.format(Locale.US, "%.2f", usdCents * usdRate / 100.0);
    }

    private long receiptUnitPriceCents(PlusOrder order) {
        if (order.getUnitPriceCents() != null) {
            return order.getUnitPriceCents();
        }
        if (order.getAmountCents() == null) {
            return 0;
        }
        if (order.getServiceFeeCents() != null) {
            return Math.max(0, order.getAmountCents() - order.getServiceFeeCents());
        }
        return order.getAmountCents();
    }

    private long receiptServiceFeeCents(PlusOrder order) {
        if (order.getServiceFeeCents() != null) {
            return order.getServiceFeeCents();
        }
        return 300;
    }

    private String formatReceiptDate(ZonedDateTime date) {
        return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
    }

    private String randomReceiptNumber() {
        String token = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        return token.substring(0, 2) + "-" + token.substring(2, 6) + "-"
                + token.substring(6, 8) + "-" + token.substring(8, 12);
    }

    private String ascii(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static class PdfCanvas {
        private final StringBuilder content = new StringBuilder();

        void text(String value, int x, int y, int size, boolean bold) {
            content.append("BT /")
                    .append(bold ? "F2" : "F1")
                    .append(" ")
                    .append(size)
                    .append(" Tf ")
                    .append(x)
                    .append(" ")
                    .append(y)
                    .append(" Td (")
                    .append(escape(value))
                    .append(") Tj ET\n");
        }

        void line(int x1, int y1, int x2, int y2) {
            content.append("0.82 0.86 0.9 RG 0.8 w ")
                    .append(x1)
                    .append(" ")
                    .append(y1)
                    .append(" m ")
                    .append(x2)
                    .append(" ")
                    .append(y2)
                    .append(" l S\n");
        }

        byte[] toPdf() {
            byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            List<byte[]> objects = new ArrayList<>();
            objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.92 841.92] "
                    + "/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>")
                    .getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(("<< /Length " + stream.length + " >>\nstream\n" + content + "endstream").getBytes(StandardCharsets.ISO_8859_1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            write(out, "%PDF-1.4\n");
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(out.size());
                write(out, (i + 1) + " 0 obj\n");
                write(out, objects.get(i));
                write(out, "\nendobj\n");
            }
            int xref = out.size();
            write(out, "xref\n0 " + (objects.size() + 1) + "\n");
            write(out, "0000000000 65535 f \n");
            for (Integer offset : offsets) {
                write(out, String.format(Locale.US, "%010d 00000 n \n", offset));
            }
            write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        }

        private static void write(ByteArrayOutputStream out, String value) {
            write(out, value.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static void write(ByteArrayOutputStream out, byte[] value) {
            out.write(value, 0, value.length);
        }
    }
}
