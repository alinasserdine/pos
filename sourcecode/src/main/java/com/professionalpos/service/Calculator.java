package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import java.math.*;
import java.util.*;

public final class Calculator {
  public record Input(
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal discount,
      boolean percent,
      BigDecimal taxRate) {}

  public record Line(
      BigDecimal gross, BigDecimal discount, BigDecimal net, BigDecimal tax, BigDecimal total) {}

  public record Total(
      List<Line> lines,
      BigDecimal gross,
      BigDecimal discount,
      BigDecimal net,
      BigDecimal tax,
      BigDecimal total) {}

  private Calculator() {}

  public static Total calculate(
      List<Input> inputs, BigDecimal orderDiscount, boolean orderPercent, String mode, int scale) {
    check(!inputs.isEmpty(), "Add at least one item.");
    nonnegative(orderDiscount, "Discount");
    List<BigDecimal> gross = new ArrayList<>(),
        discounts = new ArrayList<>(),
        after = new ArrayList<>();
    BigDecimal remaining = ZERO;
    for (Input i : inputs) {
      positive(i.quantity(), "Quantity");
      nonnegative(i.price(), "Price");
      nonnegative(i.discount(), "Discount");
      check(i.taxRate().signum() >= 0 && i.taxRate().compareTo(HUNDRED) <= 0, "Invalid tax rate.");
      BigDecimal g = round(i.quantity().multiply(i.price()), scale);
      BigDecimal d =
          round(i.percent() ? g.multiply(i.discount()).divide(HUNDRED) : i.discount(), scale);
      check(d.compareTo(g) <= 0, "Discount exceeds the item amount.");
      gross.add(g);
      discounts.add(d);
      after.add(g.subtract(d));
      remaining = remaining.add(g.subtract(d));
    }
    BigDecimal order =
        round(
            orderPercent ? remaining.multiply(orderDiscount).divide(HUNDRED) : orderDiscount,
            scale);
    check(order.compareTo(remaining) <= 0, "Discount exceeds the cart amount.");
    BigDecimal left = order, weight = remaining;
    List<Line> lines = new ArrayList<>();
    BigDecimal sumGross = ZERO, sumDisc = ZERO, sumNet = ZERO, sumTax = ZERO;
    for (int j = 0; j < inputs.size(); j++) {
      BigDecimal share =
          weight.signum() == 0
              ? ZERO
              : (j == inputs.size() - 1
                  ? left
                  : round(divide(left.multiply(after.get(j)), weight), scale)
                      .min(after.get(j))
                      .min(left));
      left = left.subtract(share);
      weight = weight.subtract(after.get(j));
      BigDecimal discounted = after.get(j).subtract(share), net = discounted, tax = ZERO;
      if (mode.equals("INCLUSIVE")) {
        net =
            round(
                divide(discounted, BigDecimal.ONE.add(inputs.get(j).taxRate().divide(HUNDRED))),
                scale);
        tax = discounted.subtract(net);
      } else if (mode.equals("EXCLUSIVE"))
        tax = round(net.multiply(inputs.get(j).taxRate()).divide(HUNDRED), scale);
      else check(mode.equals("NONE"), "Invalid tax mode.");
      BigDecimal d = discounts.get(j).add(share);
      lines.add(new Line(gross.get(j), d, net, tax, net.add(tax)));
      sumGross = sumGross.add(gross.get(j));
      sumDisc = sumDisc.add(d);
      sumNet = sumNet.add(net);
      sumTax = sumTax.add(tax);
    }
    return new Total(List.copyOf(lines), sumGross, sumDisc, sumNet, sumTax, sumNet.add(sumTax));
  }
}
