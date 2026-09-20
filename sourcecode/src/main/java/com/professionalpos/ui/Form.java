package com.professionalpos.ui;

import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.util.Money;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;

public final class Form extends JPanel {
  public record Choice(Object key, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  private final Map<String, JComponent> fields = new LinkedHashMap<>();
  private int row;
  private boolean changed;

  public Form() {
    super(new GridBagLayout());
    setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
  }

  private void addField(String key, JComponent component) {
    component.setName(key);
    GridBagConstraints g = new GridBagConstraints();
    g.gridy = row++;
    g.gridx = 0;
    g.anchor = GridBagConstraints.LINE_START;
    g.insets = new Insets(6, 6, 6, 10);
    JLabel label = Ui.label(key);
    label.setLabelFor(component);
    add(label, g);
    g.gridx = 1;
    g.weightx = 1;
    g.fill = GridBagConstraints.HORIZONTAL;
    add(component, g);
    fields.put(key, component);
    if (component instanceof JTextField t)
      t.getDocument()
          .addDocumentListener(
              new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                  changed = true;
                }

                public void removeUpdate(DocumentEvent e) {
                  changed = true;
                }

                public void changedUpdate(DocumentEvent e) {
                  changed = true;
                }
              });
    if (component instanceof JComboBox<?> c) c.addActionListener(e -> changed = true);
    if (component instanceof JCheckBox c) c.addActionListener(e -> changed = true);
  }

  public Form text(String key, Object value) {
    JTextField t = new JTextField(value == null ? "" : value.toString(), 24);
    addField(key, t);
    return this;
  }

  public Form password(String key) {
    addField(key, new JPasswordField(24));
    return this;
  }

  public Form check(String key, boolean value) {
    JCheckBox check = new JCheckBox();
    check.setSelected(value);
    addField(key, check);
    return this;
  }

  public Form options(String key, List<Choice> options, Object selected) {
    JComboBox<Choice> box = new JComboBox<>(options.toArray(Choice[]::new));
    if (selected != null)
      for (int i = 0; i < box.getItemCount(); i++)
        if (String.valueOf(box.getItemAt(i).key()).equals(String.valueOf(selected)))
          box.setSelectedIndex(i);
    box.setMaximumRowCount(15);
    addField(key, box);
    return this;
  }

  public Form choices(String key, String[] values, Object selected) {
    options(key, Arrays.stream(values).map(v -> new Choice(v, I18n.t(v))).toList(), selected);
    fields.get(key).putClientProperty("i18nChoices", Boolean.TRUE);
    return this;
  }

  public Form lookup(String key, List<Row> rows, Object selected, boolean optional) {
    List<Choice> choices = new ArrayList<>();
    if (optional) choices.add(new Choice(null, I18n.t("none")));
    for (Row r : rows) {
      Object id = r.containsKey("id") ? r.get("id") : r.get("code");
      String label = r.text("name");
      if (r.containsKey("code")) label = r.text("code") + " · " + label;
      choices.add(new Choice(id, label));
    }
    return options(key, choices, selected);
  }

  public String text(String key) {
    JComponent c = fields.get(key);
    if (c instanceof JTextField t) return t.getText().trim();
    if (c instanceof JCheckBox b) return Boolean.toString(b.isSelected());
    Object v = value(key);
    return v == null ? "" : v.toString();
  }

  public Object value(String key) {
    JComponent c = fields.get(key);
    if (c instanceof JComboBox<?> b) {
      Choice ch = (Choice) b.getSelectedItem();
      return ch == null ? null : ch.key();
    }
    if (c instanceof JCheckBox b) return b.isSelected();
    return text(key);
  }

  public Long id(String key) {
    Object v = value(key);
    return v == null || v.toString().isBlank() ? null : Long.valueOf(v.toString());
  }

  public BigDecimal decimal(String key) {
    return Money.of(text(key));
  }

  public LocalDate date(String key) {
    try {
      return LocalDate.parse(text(key));
    } catch (Exception e) {
      throw new IllegalArgumentException(I18n.t("date_hint"));
    }
  }

  public LocalDate optionalDate(String key) {
    return text(key).isBlank() ? null : date(key);
  }

  public boolean flag(String key) {
    return Boolean.TRUE.equals(value(key));
  }

  public char[] passwordValue(String key) {
    return ((JPasswordField) fields.get(key)).getPassword();
  }

  public void set(String key, Object value) {
    JComponent c = fields.get(key);
    if (c instanceof JTextField t) t.setText(value == null ? "" : value.toString());
    else if (c instanceof JComboBox<?> b)
      for (int i = 0; i < b.getItemCount(); i++)
        if (Objects.equals(String.valueOf(((Choice) b.getItemAt(i)).key()), String.valueOf(value)))
          b.setSelectedIndex(i);
  }

  public JComponent field(String key) {
    return fields.get(key);
  }

  public boolean changed() {
    return changed;
  }

  public void clean() {
    changed = false;
  }

  public Map<String, String> strings() {
    Map<String, String> m = new LinkedHashMap<>();
    for (String key : fields.keySet())
      if (!(fields.get(key) instanceof JPasswordField)) m.put(key, text(key));
    return m;
  }
}
