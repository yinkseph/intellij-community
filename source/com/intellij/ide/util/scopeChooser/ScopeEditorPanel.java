/*
 * Copyright (c) 2000-2004 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package com.intellij.ide.util.scopeChooser;

import com.intellij.ide.IdeBundle;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.packageDependencies.DependencyUISettings;
import com.intellij.packageDependencies.ui.*;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.scope.packageSet.*;
import com.intellij.ui.*;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import com.intellij.util.Icons;
import com.intellij.util.ui.Tree;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.ArrayList;

public class ScopeEditorPanel {
  public static final Icon COMPACT_EMPTY_MIDDLE_PACKAGES_ICON = IconLoader.getIcon("/objectBrowser/compactEmptyPackages.png");
  private JPanel myButtonsPanel;
  private JTextField myPatternField;
  private JPanel myTreeToolbar;
  private Tree myPackageTree;
  private JPanel myPanel;
  private JPanel myTreePanel;
  private JLabel myMatchingCountLabel;
  private JPanel myLegendPanel;

  private final Project myProject;
  private TreeExpansionMonitor myTreeExpansionMonitor;
  private TreeModelBuilder.Marker myTreeMarker;
  private PackageSet myCurrentScope = null;
  private boolean myIsInUpdate = false;
  private String myErrorMessage;
  private Alarm myUpdateAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);

  private JLabel myCaretPositionLabel;
  private int myCaretPosition = 0;
  private boolean myTextChanged = false;
  private JPanel myMatchingCountPanel;
  private PanelProgressIndicator myCurrentProgress;
  private NamedScopesHolder myHolder;

  public ScopeEditorPanel(Project project) {
    this(project, null);
  }

  public ScopeEditorPanel(Project project, final NamedScopesHolder holder) {
    myProject = project;
    myHolder = holder;
    myButtonsPanel.add(createActionsPanel());

    myPackageTree = new Tree(new RootNode());
    myTreePanel.setLayout(new BorderLayout());
    myTreePanel.add(ScrollPaneFactory.createScrollPane(myPackageTree), BorderLayout.CENTER);

    myTreeToolbar.setLayout(new BorderLayout());
    myTreeToolbar.add(createTreeToolbar(), BorderLayout.WEST);

    myTreeExpansionMonitor = TreeExpansionMonitor.install(myPackageTree, myProject);

    myTreeMarker = new TreeModelBuilder.Marker() {
      public boolean isMarked(PsiFile file) {
        return myCurrentScope != null && myCurrentScope.contains(file, getHolder());
      }
    };

    myPatternField.getDocument().addDocumentListener(new DocumentAdapter() {
      public void textChanged(DocumentEvent event) {
        onTextChange();
      }
    });

    myPatternField.addCaretListener(new CaretListener() {
      public void caretUpdate(CaretEvent e) {
        myCaretPosition = e.getDot();
        updateCaretPositionText();
      }
    });

    myPatternField.addFocusListener(new FocusListener() {
      public void focusGained(FocusEvent e) {
        myCaretPositionLabel.setVisible(true);
      }

      public void focusLost(FocusEvent e) {
        myCaretPositionLabel.setVisible(false);
      }
    });

    initTree(myPackageTree);
  }

  private void updateCaretPositionText() {
    if (myErrorMessage != null) {
      myCaretPositionLabel.setText(IdeBundle.message("label.scope.editor.caret.position", (myCaretPosition + 1)));
    }
    else {
      myCaretPositionLabel.setText("");
    }
  }

  public JPanel getPanel() {
    return myPanel;
  }

  public JPanel getTreePanel(){
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(myTreePanel, BorderLayout.CENTER);
    panel.add(myLegendPanel, BorderLayout.SOUTH);
    return panel;
  }

  public JPanel getTreeToolbar() {
    return myTreeToolbar;
  }

  private void onTextChange() {
    if (!myIsInUpdate) {
      myUpdateAlarm.cancelAllRequests();
      myCurrentScope = null;
      try {
        myCurrentScope = PackageSetFactory.getInstance().compile(myPatternField.getText());
        myErrorMessage = null;
        myTextChanged = true;
        rebuild(false);
      }
      catch (Exception e) {
        myErrorMessage = e.getMessage();
        showErrorMessage();
      }
    }
    else {
      myErrorMessage = null;
    }
  }

  private void showErrorMessage() {
    myMatchingCountLabel.setText(StringUtil.capitalize(myErrorMessage));
    myMatchingCountLabel.setForeground(Color.red);
    myMatchingCountLabel.setToolTipText(myErrorMessage);
  }

  private JComponent createActionsPanel() {
    JButton include = new JButton(IdeBundle.message("button.include"));
    JButton includeRec = new JButton(IdeBundle.message("button.include.recursively"));
    JButton exclude = new JButton(IdeBundle.message("button.exclude"));
    JButton excludeRec = new JButton(IdeBundle.message("button.exclude.recursively"));

    JPanel buttonsPanel = new JPanel(new VerticalFlowLayout());
    buttonsPanel.add(include);
    buttonsPanel.add(includeRec);
    buttonsPanel.add(exclude);
    buttonsPanel.add(excludeRec);

    include.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        includeSelected(false);
      }
    });
    includeRec.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        includeSelected(true);
      }
    });
    exclude.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        excludeSelected(false);
      }
    });
    excludeRec.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        excludeSelected(true);
      }
    });

    return buttonsPanel;
  }

  private void excludeSelected(boolean recurse) {
    final ArrayList<PackageSet> selected = getSelectedSets(recurse);
    if (selected == null || selected.isEmpty()) return;
    for (PackageSet set : selected) {
      if (myCurrentScope == null) {
        myCurrentScope = new ComplementPackageSet(set);
      } else {
        myCurrentScope = new IntersectionPackageSet(myCurrentScope, new ComplementPackageSet(set));
      }
    }
    rebuild(true);
  }

  private void includeSelected(boolean recurse) {
    final ArrayList<PackageSet> selected = getSelectedSets(recurse);
    if (selected == null || selected.isEmpty()) return;
    for (PackageSet set : selected) {
      if (myCurrentScope == null) {
        myCurrentScope = set;
      }
      else {
        myCurrentScope = new UnionPackageSet(myCurrentScope, set);
      }
    }
    rebuild(true);
  }

  @Nullable
  private ArrayList<PackageSet> getSelectedSets(boolean recursively) {
    int[] rows = myPackageTree.getSelectionRows();
    if (rows == null) return null;
    final ArrayList<PackageSet> result = new ArrayList<PackageSet>();
    for (int row : rows) {
      final PackageDependenciesNode node = (PackageDependenciesNode)myPackageTree.getPathForRow(row).getLastPathComponent();
      final PackageSet set = getNodePackageSet(node, recursively);
      if (set != null) {
        result.add(set);
      }
    }
    return result;
  }

  @Nullable
  private static PackageSet getNodePackageSet(final PackageDependenciesNode node, final boolean recursively) {
    if (node instanceof ModuleGroupNode){
      if (!recursively) return null;
      final String scope = getSelectedScopeType(node);
      final String modulePattern = node.toString();
      return scope == PatternPackageSet.SCOPE_FILE ? new PatternPackageSet(null, scope, modulePattern, "*") : new PatternPackageSet("*..*", scope, modulePattern, null);
    } else if (node instanceof ModuleNode) {
      if (!recursively) return null;
      final String scope = getSelectedScopeType(node);
      final String modulePattern = ((ModuleNode)node).getModuleName();
      return scope == PatternPackageSet.SCOPE_FILE ? new PatternPackageSet(null, scope, modulePattern, "*") : new PatternPackageSet("*..*", scope, modulePattern, null);
    }
    else if (node instanceof PackageNode) {
      String pattern = ((PackageNode)node).getPackageQName();
      if (pattern != null) {
        pattern += recursively ? "..*" : ".*";
      }
      else {
        pattern = recursively ? "*..*" : ".*";
      }

      return getPatternSet(node, pattern);
    }
    else if (node instanceof DirectoryNode){
      String pattern = ((DirectoryNode)node).getFQName();
      if (pattern != null) {
        if (pattern.length() > 0) {
          pattern += recursively ? "/*" : "*";
        } else {
          pattern += recursively ? "*/" : "*";
        }
      }
      return getPatternSet(node, pattern);
    }
    else if (node instanceof FileNode) {
      if (recursively) return null;
      FileNode fNode = (FileNode)node;
      String fqName = fNode.getFQName(getSelectedScopeType(node) == PatternPackageSet.SCOPE_FILE);
      if (fqName != null) return getPatternSet(node, fqName);
    }
    else if (node instanceof GeneralGroupNode) {
      return new PatternPackageSet("*..*", getSelectedScopeType(node), null, null);
    }

    return null;
  }

  private static PackageSet getPatternSet(PackageDependenciesNode node, String pattern) {
    String scope = getSelectedScopeType(node);
    String modulePattern = getSelectedModulePattern(node);

    return new PatternPackageSet(scope != PatternPackageSet.SCOPE_FILE ? pattern : null, scope, modulePattern, scope == PatternPackageSet.SCOPE_FILE ? pattern : null);
  }

  @Nullable
  private static String getSelectedModulePattern(PackageDependenciesNode node) {
    ModuleNode moduleParent = getModuleParent(node);
    String modulePattern = null;
    if (moduleParent != null) {
      modulePattern = moduleParent.getModuleName();
    }
    return modulePattern;
  }

  private static String getSelectedScopeType(PackageDependenciesNode node) {
    if (DependencyUISettings.getInstance().UI_GROUP_BY_FILES) return PatternPackageSet.SCOPE_FILE;
    GeneralGroupNode groupParent = getGroupParent(node);
    String scope = PatternPackageSet.SCOPE_ANY;
    if (groupParent != null) {
      String name = groupParent.toString();
      if (TreeModelBuilder.PRODUCTION_NAME.equals(name)) {
        scope = PatternPackageSet.SCOPE_SOURCE;
      }
      else if (TreeModelBuilder.TEST_NAME.equals(name)) {
        scope = PatternPackageSet.SCOPE_TEST;
      }
      else if (TreeModelBuilder.LIBRARY_NAME.equals(name)) {
        scope = PatternPackageSet.SCOPE_LIBRARY;
      }
    }
    return scope;
  }

  @Nullable
  private static GeneralGroupNode getGroupParent(PackageDependenciesNode node) {
    if (node instanceof GeneralGroupNode) return (GeneralGroupNode)node;
    if (node == null || node instanceof RootNode) return null;
    return getGroupParent((PackageDependenciesNode)node.getParent());
  }

  @Nullable
  private static ModuleNode getModuleParent(PackageDependenciesNode node) {
    if (node instanceof ModuleNode) return (ModuleNode)node;
    if (node == null || node instanceof RootNode) return null;
    return getModuleParent((PackageDependenciesNode)node.getParent());
  }

  private JComponent createTreeToolbar() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new FlattenPackagesAction());
    group.add(new CompactEmptyMiddlePackagesAction());
    group.add(new ShowFilesAction());
    group.add(new ShowModulesAction());
    group.add(new GroupByScopeTypeAction());
    group.add(new GroupByFilesAction());
    group.add(new FilterLegalsAction());

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, true);
    return toolbar.getComponent();
  }

  public void rebuild(final boolean updateText, final Runnable runnable){
    myUpdateAlarm.cancelAllRequests();
    final Runnable request = new Runnable() {
      public void run() {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          public void run() {
            myIsInUpdate = true;
            if (updateText && myCurrentScope != null) {
              myPatternField.setText(myCurrentScope.getText());
            }
            try {
              updateTreeModel();
            }
            catch (ProcessCanceledException e) {
              return;
            }
            if (runnable != null) {
              runnable.run();
            }
            myIsInUpdate = false;
          }
        });
      }
    };
    myUpdateAlarm.addRequest(request, 1000);
  }

  public void rebuild(final boolean updateText) {
    rebuild(updateText, null);
  }

  private static void initTree(Tree tree) {
    tree.setCellRenderer(new MyTreeCellRenderer());
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setLineStyleAngled();

    TreeToolTipHandler.install(tree);
    TreeUtil.installActions(tree);
    SmartExpander.installOn(tree);
    new TreeSpeedSearch(tree);
  }

  private void updateTreeModel() throws ProcessCanceledException {
    PanelProgressIndicator progress = new PanelProgressIndicator(new Consumer<JComponent>() {
      public void consume(final JComponent component) {
        setToComponent(component);
      }
    }) {
      public void start() {
        super.start();
        myTextChanged = false;
      }

      public boolean isCanceled() {
        return super.isCanceled() || myTextChanged || !myPanel.isShowing();
      }

      public void stop() {
        super.stop();
        setToComponent(myMatchingCountLabel);
      }

      public String getText() { //just show non-blocking progress
        return null;
      }

      public String getText2() {
        return null;
      }
    };
    progress.setBordersVisible(false); 
    myCurrentProgress = progress;
    Runnable updateModel = new Runnable() {
      public void run() {
        final ProcessCanceledException [] ex = new ProcessCanceledException[1];
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            try {
              myTreeExpansionMonitor.freeze();
              final TreeModelBuilder.TreeModel model = TreeModelBuilder.createTreeModel(myProject, false, false, myTreeMarker);

              if (myErrorMessage == null) {
                myMatchingCountLabel
                  .setText(IdeBundle.message("label.scope.contains.files", model.getMarkedFileCount(), model.getTotalFileCount()));
                myMatchingCountLabel.setForeground(new JLabel().getForeground());
              }
              else {
                showErrorMessage();
              }

              SwingUtilities.invokeLater(new Runnable(){
                public void run() { //not under progress
                  myPackageTree.setModel(model);
                  myTreeExpansionMonitor.restore();
                }
              });
            } catch (ProcessCanceledException e) {
              ex[0] = e;
            }
            finally {
              myCurrentProgress = null;
              //update label
              setToComponent(myMatchingCountLabel);
            }
          }
        });
        if (ex[0] != null) {
          throw ex[0];
        }
      }
    };
    ProgressManager.getInstance().runProcess(updateModel, progress);
  }

  public int getMarkedFileCount(){
    if (myErrorMessage == null) {
      return ((TreeModelBuilder.TreeModel)myPackageTree.getModel()).getMarkedFileCount();
    }
    return -1;
  }

  public void cancelCurrentProgress(){
    if (myCurrentProgress != null && myCurrentProgress.isRunning()){
      myCurrentProgress.cancel();
    }
  }

  public boolean checkCurrentScopeValid(boolean showMessage) {
    if (myCurrentScope == null) {
      if (showMessage) {
        Messages.showErrorDialog(myPanel, IdeBundle.message("error.correct.pattern.syntax.errors.first"),
                                 IdeBundle.message("title.syntax.error"));
      }
      return false;
    }
    return true;
  }

  public void apply() throws ConfigurationException {
    if (myCurrentScope == null) {
      throw new ConfigurationException(IdeBundle.message("error.correct.pattern.syntax.errors.first"));
    }
  }

  public PackageSet getCurrentScope() {
    return myCurrentScope;
  }

  public void reset(PackageSet packageSet, Runnable runnable){
    myCurrentScope = packageSet;
    myPatternField.setText(myCurrentScope == null ? "" : myCurrentScope.getText());
    rebuild(false, runnable);
  }

  private void setToComponent(final JComponent cmp) {
    myMatchingCountPanel.removeAll();
    myMatchingCountPanel.add(cmp, BorderLayout.CENTER);
    myMatchingCountPanel.revalidate();
    myMatchingCountPanel.repaint();
    SwingUtilities.invokeLater(new Runnable(){
      public void run() {
        myPatternField.requestFocusInWindow();        
      }
    });
  }

  public void restoreCanceledProgress() {
    if (myIsInUpdate) {
      rebuild(false);
    }
  }

  public void clearCaches() {
    TreeModelBuilder.clearCaches(myProject);
  }

  public NamedScopesHolder getHolder() {
    return myHolder;
  }

  private static class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    private static final Color WHOLE_INCLUDED = new Color(10, 119, 0);
    private static final Color PARTIAL_INCLUDED = new Color(0, 50, 160);

    public void customizeCellRenderer(JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      if (value instanceof PackageDependenciesNode) {
        PackageDependenciesNode node = (PackageDependenciesNode)value;
        if (expanded) {
          setIcon(node.getOpenIcon());
        }
        else {
          setIcon(node.getClosedIcon());
        }

        setForeground(selected && hasFocus ? UIUtil.getTreeSelectionForeground() : UIUtil.getTreeForeground());
        if (!selected && node.hasMarked() && !DependencyUISettings.getInstance().UI_FILTER_LEGALS) {
          setForeground(node.hasUnmarked() ? PARTIAL_INCLUDED : WHOLE_INCLUDED);
        }
        if (node instanceof DirectoryNode) {
          final DirectoryNode directoryNode = (DirectoryNode)node;
          append(directoryNode.getDirName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
          final String locationString = directoryNode.getLocationString();
          if (locationString != null) {
            append(" (" + locationString + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
          }
        }
        else {
          append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }
      }
    }
  }

  private final class FlattenPackagesAction extends ToggleAction {
    FlattenPackagesAction() {
      super(IdeBundle.message("action.flatten.packages"),
            IdeBundle.message("action.flatten.packages"), Icons.FLATTEN_PACKAGES_ICON);
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_FLATTEN_PACKAGES;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_FLATTEN_PACKAGES = flag;
      rebuild(true);
    }
  }


  private final class CompactEmptyMiddlePackagesAction extends ToggleAction {
    CompactEmptyMiddlePackagesAction() {
      super(IdeBundle.message("action.compact.empty.middle.packages"),
            IdeBundle.message("action.compact.empty.middle.packages"), COMPACT_EMPTY_MIDDLE_PACKAGES_ICON);
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_COMPACT_EMPTY_MIDDLE_PACKAGES;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_COMPACT_EMPTY_MIDDLE_PACKAGES = flag;
      rebuild(true);
    }

    public void update(final AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabled(DependencyUISettings.getInstance().UI_GROUP_BY_FILES);
    }
  }

  private final class ShowFilesAction extends ToggleAction {
    ShowFilesAction() {
      super(IdeBundle.message("action.show.files"),
            IdeBundle.message("action.description.show.files"), IconLoader.getIcon("/fileTypes/java.png"));
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_SHOW_FILES;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_SHOW_FILES = flag;
      rebuild(true);
    }
  }

  private final class GroupByScopeTypeAction extends ToggleAction {
    GroupByScopeTypeAction() {
      super(IdeBundle.message("action.group.by.scope.type"),
            IdeBundle.message("action.description.group.by.scope"), IconLoader.getIcon("/nodes/testSourceFolder.png"));
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_GROUP_BY_SCOPE_TYPE;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_GROUP_BY_SCOPE_TYPE = flag;
      rebuild(true);
    }

    public void update(final AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabled(!DependencyUISettings.getInstance().UI_GROUP_BY_FILES);
    }
  }

  private final class GroupByFilesAction extends ToggleAction{
    GroupByFilesAction() {
      super(IdeBundle.message("action.show.file.structure"),
            IdeBundle.message("action.description.show.file.structure"), IconLoader.getIcon("/objectBrowser/showGlobalInspections.png"));
    }

    public boolean isSelected(AnActionEvent e) {
      return DependencyUISettings.getInstance().UI_GROUP_BY_FILES;
    }

    public void setSelected(AnActionEvent e, boolean flag) {
      final DependencyUISettings settings = DependencyUISettings.getInstance();
      settings.UI_GROUP_BY_FILES = flag;
      if (flag){
        settings.UI_GROUP_BY_SCOPE_TYPE = false;
        settings.UI_SHOW_MODULES = true;
      }
      clearCaches(); //need to reconsider libraries files 
      rebuild(true);
    }
  }

  private final class ShowModulesAction extends ToggleAction {
    ShowModulesAction() {
      super(IdeBundle.message("action.show.modules"),
            IdeBundle.message("action.description.show.modules"), IconLoader.getIcon("/objectBrowser/showModules.png"));
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_SHOW_MODULES;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_SHOW_MODULES = flag;
      rebuild(true);
    }

    public void update(final AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabled(!DependencyUISettings.getInstance().UI_GROUP_BY_FILES);
    }
  }

  private final class FilterLegalsAction extends ToggleAction {
    FilterLegalsAction() {
      super(IdeBundle.message("action.show.included.only"),
            IdeBundle.message("action.description.show.included.only"), IconLoader.getIcon("/ant/filter.png"));
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_FILTER_LEGALS;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_FILTER_LEGALS = flag;
      UIUtil.setEnabled(myLegendPanel, !flag, true);
      rebuild(true);
    }
  }
}