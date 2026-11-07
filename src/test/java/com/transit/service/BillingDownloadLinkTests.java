package com.transit.service;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
class BillingDownloadLinkTests {
 @Test void bothDocumentsContainClickableAccountProtectedDownloadLink() throws Exception {
    boolean cjk=Files.isRegularFile(Path.of("C:/Windows/Fonts/simhei.ttf"));
    var data=new BillingPdfRenderer.DocumentData("INV-20261107-7","1234-5678-9012","November 7, 2026","Linknux Test","Test Street","Test City","China","billing@example.com",cjk?"测试买家":"Test Buyer",cjk?"测试路一号":"Test Street",cjk?"南山区":"District",cjk?"深圳市":"City",cjk?"广东省":"Province","518000","China","buyer@example.com","ChatGPT Pro", "1,250.01 CNY","WeChat Pay");
    String url="https://linknux.com/billing/service/7";
    var renderer=new BillingPdfRenderer("",url);
    var dir=Path.of("target/test-artifacts/billing-links");Files.createDirectories(dir);
    for(String type:java.util.List.of("invoice","receipt")) {
      byte[] bytes=type.equals("invoice")?renderer.renderInvoice(data):renderer.renderReceipt(data);
      Files.write(dir.resolve(type+".pdf"),bytes);
      try(var pdf=Loader.loadPDF(bytes)){
        assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        var link=(PDAnnotationLink)pdf.getPage(0).getAnnotations().get(0);
        assertThat(((PDActionURI)link.getAction()).getURI()).isEqualTo(url);
        ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0,110),"png",dir.resolve(type+".png").toFile());
      }
    }
 }
}
