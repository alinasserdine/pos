package com.professionalpos.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class Trade {
  private Trade() {}

  public record Line(
      long productId,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal discount,
      boolean percent,
      String note,
      Long poLineId) {
    public Line(long productId, BigDecimal quantity, BigDecimal price) {
      this(productId, quantity, price, BigDecimal.ZERO, false, "", null);
    }
  }

  public record Payment(String method, BigDecimal amount, BigDecimal tendered, String reference) {
    public Payment(String method, BigDecimal amount) {
      this(method, amount, amount, "");
    }
  }

  public record Request(
      String kind,
      Long partyId,
      LocalDate date,
      LocalDate due,
      String currency,
      List<Line> lines,
      BigDecimal discount,
      boolean discountPercent,
      List<Payment> payments,
      String reference,
      String notes,
      String requestKey,
      Long heldId,
      Long orderId,
      User approver,
      String reason) {
    public Request {
      lines = List.copyOf(lines);
      payments = List.copyOf(payments);
    }
  }

  public record ReturnLine(long originalLineId, BigDecimal quantity, String disposition) {}

  public record ReturnRequest(
      long originalId,
      List<ReturnLine> lines,
      String method,
      String reason,
      LocalDate date,
      String requestKey,
      User approver,
      boolean voidSale) {
    public ReturnRequest {
      lines = List.copyOf(lines);
    }
  }

  public record Result(
      long id,
      String number,
      BigDecimal total,
      BigDecimal paid,
      BigDecimal credit,
      BigDecimal change,
      BigDecimal cogs) {}
}
