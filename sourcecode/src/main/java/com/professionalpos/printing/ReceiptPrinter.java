package com.professionalpos.printing;

import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.service.QueryService;
import java.awt.*;
import java.awt.font.*;
import java.awt.print.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class ReceiptPrinter implements Printable {
  private final List<String> lines = new ArrayList<>();
  private final String width;
  private final boolean arabic;
  private java.awt.image.BufferedImage logo;
  private int decimals;

  private String number(BigDecimal value) {
    return value.setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  public ReceiptPrinter(QueryService.Detail detail, String width, String language) {
    this.width = width;
    arabic = language.equals("ar") || language.equals("both");
    Row d = detail.header();
    decimals = (int) d.number("decimal_places");
    if (!d.text("logo_path").isBlank())
      try {
        logo = javax.imageio.ImageIO.read(Path.of(d.text("logo_path")).toFile());
      } catch (Exception ignored) {
      }
    lines.add(d.text("store_name"));
    for (String k : List.of("store_address", "store_phone", "store_tax_number"))
      if (!d.text(k).isBlank()) lines.add(d.text(k));
    lines.add("");
    lines.add(label("Invoice", "فاتورة", language) + ": " + d.text("number"));
    String timestamp = d.text("created_at");
    lines.add(
        d.text("document_date")
            + "  "
            + (timestamp.length() >= 19 ? timestamp.substring(11, 19) : timestamp));
    lines.add(label("Cashier", "أمين الصندوق", language) + ": " + d.text("cashier"));
    lines.add(label("Customer", "العميل", language) + ": " + d.text("party_name"));
    if (!d.text("party_tax_number").isBlank()) lines.add("Tax: " + d.text("party_tax_number"));
    lines.add("");
    for (Row l : detail.lines()) {
      lines.add(
          arabic && !l.text("product_name_ar").isBlank()
              ? l.text("product_name_ar")
              : l.text("product_name"));
      lines.add(
          l.money("quantity").stripTrailingZeros().toPlainString()
              + " x "
              + number(l.money("unit_price"))
              + " = "
              + number(l.money("total")));
      if (l.money("discount").signum() > 0)
        lines.add(label("Discount", "حسم", language) + ": " + number(l.money("discount")));
      if (l.money("tax").signum() > 0)
        lines.add(
            label("Tax", "ضريبة", language)
                + ": "
                + number(l.money("tax"))
                + " ("
                + l.money("tax_rate").stripTrailingZeros()
                + "%)");
      if (!l.text("note").isBlank()) lines.add(l.text("note"));
    }
    lines.add("");
    lines.add(label("Net", "الصافي", language) + ": " + number(d.money("net")));
    lines.add(label("Tax", "الضريبة", language) + ": " + number(d.money("tax")));
    lines.add(
        label("TOTAL", "المجموع", language)
            + ": "
            + number(d.money("total"))
            + " "
            + d.text("currency_code"));
    BigDecimal paid = BigDecimal.ZERO;
    for (Row p : detail.payments()) {
      lines.add(
          I18n.t(p.text("method_code"))
              + ": "
              + number(p.money("amount"))
              + " "
              + p.text("currency_code"));
      paid = paid.add(p.money("amount"));
      if (p.money("change_amount").signum() > 0) {
        lines.add(
            label("Received", "المبلغ المستلم", language) + ": " + number(p.money("tendered")));
        lines.add(
            label("Change", "الباقي", language)
                + ": "
                + number(p.money("change_amount"))
                + " "
                + p.text("currency_code"));
      }
    }
    if (d.money("total").subtract(paid).signum() > 0)
      lines.add(
          label("Account / debt reduction", "الحساب / تخفيض الدين", language)
              + ": "
              + number(d.money("total").subtract(paid)));
    if (d.money("rate_to_base").compareTo(BigDecimal.ONE) != 0)
      lines.add(
          label("Rate to base", "سعر التحويل إلى العملة الأساسية", language)
              + ": "
              + d.money("rate_to_base").stripTrailingZeros().toPlainString());
    lines.add("");
    lines.add(d.text("footer"));
  }

  private static String label(String en, String ar, String lang) {
    return lang.equals("both") ? en + " / " + ar : lang.equals("ar") ? ar : en;
  }

  public String text() {
    return String.join("\n", lines);
  }

  public void save(Path path) throws Exception {
    Files.writeString(path, text(), StandardCharsets.UTF_8);
  }

  public JComponent preview() {
    JTextArea area = new JTextArea(text());
    area.setEditable(false);
    area.setFont(new Font("SansSerif", Font.PLAIN, 15));
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setMargin(new Insets(24, 24, 24, 24));
    area.applyComponentOrientation(
        arabic ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
    JPanel panel = new JPanel(new java.awt.BorderLayout());
    panel.add(new JScrollPane(area));
    if (logo != null) {
      double scale = Math.min(260.0 / logo.getWidth(), 90.0 / logo.getHeight());
      JLabel image =
          new JLabel(
              new ImageIcon(
                  logo.getScaledInstance(
                      (int) (logo.getWidth() * scale),
                      (int) (logo.getHeight() * scale),
                      Image.SCALE_SMOOTH)));
      image.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      panel.add(image, java.awt.BorderLayout.NORTH);
    }
    return panel;
  }

  public PageFormat format() {
    PageFormat format = new PageFormat();
    Paper paper = new Paper();
    double w = width.equals("A4") ? 595.28 : Double.parseDouble(width) * 72 / 25.4;
    double h = 841.89;
    paper.setSize(w, h);
    paper.setImageableArea(9, 14, w - 18, h - 28);
    format.setPaper(paper);
    return format;
  }

  public void printReceipt(Component parent) throws PrinterException {
    PrinterJob job = PrinterJob.getPrinterJob();
    job.setJobName("Professional POS receipt");
    job.setPrintable(this, format());
    if (job.printDialog()) job.print();
  }

  @Override
  public int print(Graphics graphics, PageFormat page, int index) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.translate(page.getImageableX(), page.getImageableY());
      g.setColor(Color.BLACK);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      Font font = new Font("SansSerif", Font.PLAIN, width.equals("A4") ? 11 : 9);
      FontRenderContext frc = g.getFontRenderContext();
      List<TextLayout> layouts = new ArrayList<>();
      for (String line : lines) {
        if (line.isBlank()) {
          layouts.add(new TextLayout(" ", font, frc));
          continue;
        }
        java.text.AttributedString attributed = new java.text.AttributedString(line);
        attributed.addAttribute(java.awt.font.TextAttribute.FONT, font);
        LineBreakMeasurer m = new LineBreakMeasurer(attributed.getIterator(), frc);
        while (m.getPosition() < line.length())
          layouts.add(m.nextLayout((float) page.getImageableWidth()));
      }
      float logoSpace = logo == null ? 0 : 64;
      float lineHeight = font.getSize2D() + 5;
      int perPage = Math.max(1, (int) ((page.getImageableHeight() - logoSpace) / lineHeight)),
          start = index * perPage;
      if (start >= layouts.size()) return NO_SUCH_PAGE;
      if (logo != null && index == 0) {
        double scale =
            Math.min(page.getImageableWidth() / logo.getWidth(), 55.0 / logo.getHeight());
        int lw = (int) (logo.getWidth() * scale), lh = (int) (logo.getHeight() * scale);
        g.drawImage(logo, ((int) page.getImageableWidth() - lw) / 2, 0, lw, lh, null);
      }
      float y = lineHeight + logoSpace;
      for (int i = start; i < Math.min(start + perPage, layouts.size()); i++) {
        TextLayout l = layouts.get(i);
        l.draw(g, arabic ? (float) page.getImageableWidth() - l.getAdvance() : 0, y);
        y += lineHeight;
      }
      return PAGE_EXISTS;
    } finally {
      g.dispose();
    }
  }
}
