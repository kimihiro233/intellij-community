/*
 * Copyright 2000-2005 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.help.HelpManager;

import javax.swing.*;
import java.awt.*;

/**
 * Created by IntelliJ IDEA.
 * User: alex
 * Date: 04.07.2005
 * Time: 14:39:24
 * To change this template use File | Settings | File Templates.
 */
public class LockDialog extends DialogWrapper {
  private JTextArea myLockTextArea;
  private JCheckBox myForceCheckBox;

  private static final String HELP_ID = "vcs.subversion.lock";

  public LockDialog(Project project, boolean canBeParent) {
    super(project, canBeParent);
    setTitle("Lock Files");
    setResizable(true);

    getHelpAction().setEnabled(true);
    init();

  }

  protected Action[] createActions() {
    return new Action[]{getOKAction(), getCancelAction(), getHelpAction()};
  }

  protected void doHelpAction() {
    HelpManager.getInstance().invokeHelp(HELP_ID);
  }

  public boolean shouldCloseOnCross() {
    return true;
  }

  public String getComment() {
    return myLockTextArea.getText();
  }

  public boolean isForce() {
    return myForceCheckBox.isSelected();
  }

  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());

    GridBagConstraints gc = new GridBagConstraints();
    gc.insets = new Insets(2, 2, 2, 2);
    gc.gridwidth = 1;
    gc.gridheight = 1;
    gc.gridx = 0;
    gc.gridy = 0;
    gc.anchor = GridBagConstraints.WEST;
    gc.fill = GridBagConstraints.NONE;
    gc.weightx = 0;
    gc.weighty = 0;

    JLabel commentLabel = new JLabel("&Lock comment:");
    panel.add(commentLabel, gc);

    gc.gridy += 1;
    gc.weightx = 1;
    gc.weighty = 1;
    gc.fill = GridBagConstraints.BOTH;

    myLockTextArea = new JTextArea(7, 25);
    JScrollPane scrollPane = new JScrollPane(myLockTextArea, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                                             JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scrollPane.setMinimumSize(scrollPane.getPreferredSize());
    panel.add(scrollPane, gc);
    DialogUtil.registerMnemonic(commentLabel, myLockTextArea);

    gc.gridy += 1;
    gc.weightx = 0;
    gc.weighty = 0;
    gc.fill = GridBagConstraints.NONE;

    myForceCheckBox = new JCheckBox("&Steal existing lock");
    panel.add(myForceCheckBox, gc);
    DialogUtil.registerMnemonic(myForceCheckBox);

    gc.gridy += 1;
    gc.weightx = 1;
    gc.fill = GridBagConstraints.HORIZONTAL;
    panel.add(new JSeparator(), gc);

    return panel;
  }

  protected String getDimensionServiceKey() {
    return "svn.lockDialog";
  }

  public JComponent getPreferredFocusedComponent() {
    return myLockTextArea;
  }
}
