package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.awt.*;
import java.util.Arrays;
import javax.swing.*;

public final class LoginDialog {
  private LoginDialog() {}

  public static User show(Component owner, App app, Permission approval) {
    User[] user = {null};
    Form form =
        new Form()
            .text("username", approval == null && app.user != null ? app.user.username() : "")
            .password("password");
    JPanel root = new JPanel(new BorderLayout(10, 16));
    root.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
    JLabel title = Ui.label(approval == null ? "welcome_back" : "manager_approval");
    title.setFont(new Font("SansSerif", Font.BOLD, 25));
    root.add(title, BorderLayout.NORTH);
    root.add(form, BorderLayout.CENTER);
    JDialog dialog =
        Ui.dialog(owner, approval == null ? "sign_in" : "manager_approval", root, 480, 330);
    JButton sign =
        Ui.button(
            "sign_in",
            () -> {
              String name = form.text("username");
              char[] pw = form.passwordValue("password");
              Ui.work(
                  dialog,
                  () -> {
                    try {
                      return approval == null
                          ? app.auth.login(name, pw)
                          : app.auth.approve(name, pw, approval);
                    } finally {
                      Arrays.fill(pw, '\0');
                    }
                  },
                  u -> {
                    user[0] = u;
                    dialog.dispose();
                  });
            });
    Theme.primary(sign);
    root.add(Ui.bar(Ui.button("cancel", dialog::dispose), sign), BorderLayout.SOUTH);
    dialog.getRootPane().setDefaultButton(sign);
    dialog.setVisible(true);
    return user[0];
  }
}
