package com.modcrafting.luyten;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import com.strobel.assembler.InputTypeLoader;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.core.StringUtilities;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;

/**
 * Jar-level model
 */
public class Model extends JSplitPane {
	private static final long serialVersionUID = 6896857630400910200L;

	private static final long MAX_JAR_FILE_SIZE_BYTES = 1_000_000_000;
	private static final long MAX_UNPACKED_FILE_SIZE_BYTES = 1_000_000;

	private final LuytenTypeLoader typeLoader = new LuytenTypeLoader();
	private MetadataSystem metadataSystem = new MetadataSystem(typeLoader);

	private JTree tree;
	private JTabbedPane house;
	private File file;
	private DecompilerSettings settings;
	private DecompilationOptions decompilationOptions;
	private Theme theme;
	private JProgressBar bar;
	private JLabel label;
	private HashSet<OpenFile> hmap = new HashSet<OpenFile>();
	private boolean open = false;
	private State state;
	private ConfigSaver configSaver;
	private LuytenPreferences luytenPrefs;

	public Model(MainWindow mainWindow) {
		this.bar = mainWindow.getBar();
		this.label = mainWindow.getLabel();

		configSaver = ConfigSaver.getLoadedInstance();
		settings = configSaver.getDecompilerSettings();
		luytenPrefs = configSaver.getLuytenPreferences();

		try {
			String themeXml = luytenPrefs.getThemeXml();
			theme = Theme.load(getClass().getResourceAsStream(LuytenPreferences.THEME_XML_PATH + themeXml));
		} catch (Exception e1) {
			try {
				e1.printStackTrace();
				String themeXml = LuytenPreferences.DEFAULT_THEME_XML;
				luytenPrefs.setThemeXml(themeXml);
				theme = Theme.load(getClass().getResourceAsStream(LuytenPreferences.THEME_XML_PATH + themeXml));
			} catch (Exception e2) {
				e2.printStackTrace();
			}
		}

		tree = new JTree();
		tree.setModel(new DefaultTreeModel(null));
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setCellRenderer(new CellRenderer());
		TreeListener tl = new TreeListener();
		tree.addMouseListener(tl);

		JPanel panel2 = new JPanel();
		panel2.setLayout(new BoxLayout(panel2, 1));
		panel2.setBorder(BorderFactory.createTitledBorder("Structure"));
		panel2.add(new JScrollPane(tree));

		house = new JTabbedPane();
		house.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, 1));
		panel.setBorder(BorderFactory.createTitledBorder("Code"));
		panel.add(house);
		this.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
		this.setDividerLocation(250 % mainWindow.getWidth());
		this.setLeftComponent(panel2);
		this.setRightComponent(panel);

		decompilationOptions = new DecompilationOptions();
		decompilationOptions.setSettings(settings);
		decompilationOptions.setFullDecompilation(true);
	}

	public void showLegal(String legalStr) {
		OpenFile open = new OpenFile("Legal", "*/Legal", legalStr, theme);
		hmap.add(open);
		addOrSwitchToTab(open);
	}

	private void addOrSwitchToTab(OpenFile open) {
		String title = open.name;
		RTextScrollPane rTextScrollPane = open.scrollPane;
		if (house.indexOfTab(title) < 0) {
			house.addTab(title, rTextScrollPane);
			house.setSelectedIndex(house.indexOfTab(title));
			int index = house.indexOfTab(title);
			Tab ct = new Tab(title);
			ct.getButton().addMouseListener(new CloseTab(title));
			house.setTabComponentAt(index, ct);
		} else {
			house.setSelectedIndex(house.indexOfTab(title));
		}
	}

	private void closeOpenTab(int index) {
		RTextScrollPane co = (RTextScrollPane) house.getComponentAt(index);
		RSyntaxTextArea pane = (RSyntaxTextArea) co.getViewport().getView();
		OpenFile open = null;
		for (OpenFile file : hmap)
			if (pane.equals(file.textArea))
				open = file;
		if (open != null && hmap.contains(open))
			hmap.remove(open);
		house.remove(co);
	}

	private String getName(String path) {
		if (path == null)
			return "";
		int i = path.lastIndexOf("/");
		if (i == -1)
			i = path.lastIndexOf("\\");
		if (i != -1)
			return path.substring(i + 1);
		return path;
	}

	private class TreeListener extends MouseAdapter {
		@Override
		public void mouseClicked(MouseEvent event) {
			final TreePath trp = tree.getPathForLocation(event.getX(), event.getY());
			if (trp == null)
				return;
			if (SwingUtilities.isLeftMouseButton(event)
					&& event.getClickCount() == 2) {
				new Thread() {
					public void run() {
						openEntryByTreePath(trp);
					}
				}.start();
			} else {
				tree.getSelectionModel().setSelectionPath(trp);
			}
		}
	}

	private void openEntryByTreePath(TreePath trp) {
		String st = trp.toString().replace(file.getName(), "");
		final String[] args = st.replace("[", "").replace("]", "").split(",");
		String name = "";
		String path = "";
		try {
			bar.setVisible(true);
			if (args.length > 1) {
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < args.length; i++) {
					if (i == args.length - 1) {
						name = args[i].trim();
					} else {
						sb.append(args[i].trim()).append("/");
					}
				}
				path = sb.toString().replace(".", "/") + name;

				if (file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) {
					if (state == null) {
						JarFile jfile = new JarFile(file);
						ITypeLoader jarLoader = new JarTypeLoader(jfile);

						typeLoader.getTypeLoaders().add(jarLoader);
						state = new State(file.getCanonicalPath(), file, jfile, jarLoader);
					}

					JarEntry entry = state.jarFile.getJarEntry(path);
					if (entry == null) {
						throw new FileEntryNotFoundException();
					}
					if (entry.getSize() > MAX_UNPACKED_FILE_SIZE_BYTES) {
						throw new TooLargeFileException(entry.getSize());
					}

					if (entry.getName().endsWith(".class")) {
						label.setText("Extracting: " + name);
						String internalName = StringUtilities.removeRight(entry.getName(), ".class");
						TypeReference type = metadataSystem.lookupType(internalName);
						extractClassToTextPane(type, name, path);
					} else {
						label.setText("Opening: " + name);
						try (InputStream in = state.jarFile.getInputStream(entry);) {
							extractSimpleFileEntryToTextPane(in, name, path);
						}
					}
				}
			} else {
				name = file.getName();
				path = file.getPath().replaceAll("\\\\", "/");
				if (file.length() > MAX_UNPACKED_FILE_SIZE_BYTES) {
					throw new TooLargeFileException(file.length());
				}
				if (name.endsWith(".class")) {
					label.setText("Extracting: " + name);
					TypeReference type = metadataSystem.lookupType(path);
					extractClassToTextPane(type, name, path);
				} else {
					label.setText("Opening: " + name);
					try (InputStream in = new FileInputStream(file);) {
						extractSimpleFileEntryToTextPane(in, name, path);
					}
				}
			}
			label.setText("Complete");
		} catch (FileEntryNotFoundException e) {
			label.setText("File not found: " + name);
		} catch (FileIsBinaryException e) {
			label.setText("Binary resource: " + name);
		} catch (TooLargeFileException e) {
			label.setText("File is too large: " + name + " - size: " + e.getReadableFileSize());
		} catch (Exception e) {
			label.setText("Cannot open: " + name);
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, e.toString(), "Error!", JOptionPane.ERROR_MESSAGE);
		} finally {
			bar.setVisible(false);
		}
	}

	private void extractClassToTextPane(TypeReference type, String tabTitle, String path) throws Exception {
		if (tabTitle == null || tabTitle.trim().length() < 1 || path == null) {
			throw new FileEntryNotFoundException();
		}
		OpenFile sameTitledOpen = null;
		for (OpenFile nextOpen : hmap) {
			if (tabTitle.equals(nextOpen.name)) {
				sameTitledOpen = nextOpen;
				break;
			}
		}
		if (sameTitledOpen != null && path.equals(sameTitledOpen.getPath())) {
			addOrSwitchToTab(sameTitledOpen);
			return;
		}

		// build tab content: do decompilation
		// synchronized: do not accept changes from menu while running
		String decompiledSource;
		synchronized (settings) {
			TypeDefinition resolvedType = null;
			if (type == null || ((resolvedType = type.resolve()) == null)) {
				throw new Exception("Unable to resolve type.");
			}
			StringWriter stringwriter = new StringWriter();
			settings.getLanguage().decompileType(resolvedType,
					new PlainTextOutput(stringwriter), decompilationOptions);
			decompiledSource = stringwriter.toString();
		}

		// open tab
		if (sameTitledOpen != null) {
			sameTitledOpen.setContent(decompiledSource);
			sameTitledOpen.setPath(path);
			addOrSwitchToTab(sameTitledOpen);
		} else {
			OpenFile open = new OpenFile(tabTitle, path, decompiledSource, theme);
			hmap.add(open);
			addOrSwitchToTab(open);
		}
	}

	private void extractSimpleFileEntryToTextPane(InputStream inputStream, String tabTitle, String path)
			throws Exception {
		if (inputStream == null || tabTitle == null || tabTitle.trim().length() < 1 || path == null) {
			throw new FileEntryNotFoundException();
		}
		OpenFile sameTitledOpen = null;
		for (OpenFile nextOpen : hmap) {
			if (tabTitle.equals(nextOpen.name)) {
				sameTitledOpen = nextOpen;
				break;
			}
		}
		if (sameTitledOpen != null && path.equals(sameTitledOpen.getPath())) {
			addOrSwitchToTab(sameTitledOpen);
			return;
		}

		// build tab content
		StringBuilder sb = new StringBuilder();
		long nonprintableCharactersCount = 0;
		try (InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
				BufferedReader reader = new BufferedReader(inputStreamReader);) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append("\n");

				for (byte nextByte : line.getBytes()) {
					if (nextByte <= 0) {
						nonprintableCharactersCount++;
					}
				}

			}
		}

		// guess binary or text
		String extension = "." + tabTitle.replaceAll("^[^\\.]*$", "").replaceAll("[^\\.]*\\.", "");
		boolean isTextFile = (OpenFile.WELL_KNOWN_TEXT_FILE_EXTENSIONS.contains(extension) ||
				nonprintableCharactersCount < sb.length() / 5);
		if (!isTextFile) {
			throw new FileIsBinaryException();
		}

		// open tab
		if (sameTitledOpen != null) {
			sameTitledOpen.setContent(sb.toString());
			sameTitledOpen.setPath(path);
			addOrSwitchToTab(sameTitledOpen);
		} else {
			OpenFile open = new OpenFile(tabTitle, path, sb.toString(), theme);
			hmap.add(open);
			addOrSwitchToTab(open);
		}
	}

	private final class State implements AutoCloseable {
		private final String key;
		private final File file;
		final JarFile jarFile;
		final ITypeLoader typeLoader;

		private State(String key, File file, JarFile jarFile, ITypeLoader typeLoader) {
			this.key = VerifyArgument.notNull(key, "key");
			this.file = VerifyArgument.notNull(file, "file");
			this.jarFile = jarFile;
			this.typeLoader = typeLoader;
		}

		@Override
		public void close() {
			if (typeLoader != null) {
				Model.this.typeLoader.getTypeLoaders().remove(typeLoader);
			}
			Closer.tryClose(jarFile);
		}

		@SuppressWarnings("unused")
		public File getFile() {
			return file;
		}

		@SuppressWarnings("unused")
		public String getKey() {
			return key;
		}
	}

	private class Tab extends JPanel {
		private static final long serialVersionUID = -514663009333644974L;
		private JLabel closeButton = new JLabel(new ImageIcon(Toolkit.getDefaultToolkit().getImage(
				this.getClass().getResource("/resources/icon_close.png"))));
		private JLabel tabTitle = new JLabel();
		private String title = "";

		public Tab(String t) {
			super(new GridBagLayout());
			this.setOpaque(false);

			this.title = t;
			this.tabTitle = new JLabel(title);

			this.createTab();
		}

		public JLabel getButton() {
			return this.closeButton;
		}

		public void createTab() {
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 1;
			this.add(tabTitle, gbc);
			gbc.gridx++;
			gbc.insets = new Insets(0, 5, 0, 0);
			gbc.anchor = GridBagConstraints.EAST;
			this.add(closeButton, gbc);
		}
	}

	private class CloseTab extends MouseAdapter {
		String title;

		public CloseTab(String title) {
			this.title = title;
		}

		@Override
		public void mouseClicked(MouseEvent e) {
			int index = house.indexOfTab(title);
			closeOpenTab(index);
		}
	}

	private DefaultMutableTreeNode load(DefaultMutableTreeNode node, List<String> args) {
		if (args.size() > 0) {
			String name = args.remove(0);
			DefaultMutableTreeNode nod = getChild(node, name);
			if (nod == null)
				nod = new DefaultMutableTreeNode(name);
			node.add(load(nod, args));
		}
		return node;
	}

	@SuppressWarnings("unchecked")
	private DefaultMutableTreeNode getChild(DefaultMutableTreeNode node, String name) {
		Enumeration<DefaultMutableTreeNode> entry = node.children();
		while (entry.hasMoreElements()) {
			DefaultMutableTreeNode nods = entry.nextElement();
			if (nods.getUserObject().equals(name)) {
				return nods;
			}
		}
		return null;
	}

	public boolean loadFile(File file) {
		if (open)
			closeFile();
		this.file = file;
		loadTree();
		return open;
	}

	public void loadTree() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					if (file == null) {
						return;
					}
					if (file.length() > MAX_JAR_FILE_SIZE_BYTES) {
						throw new TooLargeFileException(file.length());
					}
					if (file.getName().endsWith(".zip") || file.getName().endsWith(".jar")) {
						JarFile jfile;
						jfile = new JarFile(file);
						label.setText("Loading: " + jfile.getName());
						bar.setVisible(true);
						Enumeration<JarEntry> entry = jfile.entries();
						DefaultMutableTreeNode top = new DefaultMutableTreeNode(getName(file.getName()));
						List<String> mass = new ArrayList<String>();
						while (entry.hasMoreElements()) {
							JarEntry e = entry.nextElement();
							if (!e.isDirectory())
								mass.add(e.getName());

						}
						List<String> sort = new ArrayList<String>();
						Collections.sort(mass, String.CASE_INSENSITIVE_ORDER);
						for (String m : mass)
							if (m.contains("META-INF") && !sort.contains(m))
								sort.add(m);
						Set<String> set = new HashSet<String>();
						for (String m : mass) {
							if (m.contains("/")) {
								set.add(m.substring(0, m.lastIndexOf("/") + 1));
							}
						}
						List<String> packs = Arrays.asList(set.toArray(new String[] {}));
						Collections.sort(packs, String.CASE_INSENSITIVE_ORDER);
						Collections.sort(packs, new Comparator<String>() {
							public int compare(String o1, String o2) {
								return o2.split("/").length - o1.split("/").length;
							}
						});
						for (String pack : packs)
							for (String m : mass)
								if (!m.contains("META-INF") && m.contains(pack)
										&& !m.replace(pack, "").contains("/"))
									sort.add(m);
						for (String m : mass)
							if (!m.contains("META-INF") && !m.contains("/") && !sort.contains(m))
								sort.add(m);
						for (String pack : sort) {
							LinkedList<String> list = new LinkedList<String>(Arrays.asList(pack.split("/")));
							load(top, list);
						}
						tree.setModel(new DefaultTreeModel(top));
						if (state == null) {
							ITypeLoader jarLoader = new JarTypeLoader(jfile);
							typeLoader.getTypeLoaders().add(jarLoader);
							state = new State(file.getCanonicalPath(), file, jfile, jarLoader);
						}
						open = true;
						label.setText("Complete");
					} else {
						DefaultMutableTreeNode top = new DefaultMutableTreeNode(getName(file.getName()));
						tree.setModel(new DefaultTreeModel(top));
						settings.setTypeLoader(new InputTypeLoader());
						open = true;
						label.setText("Complete");
					}
				} catch (TooLargeFileException e) {
					label.setText("File is too large: " + file.getName() + " - size: " + e.getReadableFileSize());
				} catch (Exception e1) {
					label.setText("Cannot open: " + file.getName());
					e1.printStackTrace();
				} finally {
					bar.setVisible(false);
				}
			}

		}).start();
	}

	public void closeFile() {
		for (OpenFile co : hmap) {
			int pos = house.indexOfTab(co.name);
			if (pos >= 0)
				house.remove(pos);
		}

		final State oldState = state;
		Model.this.state = null;
		if (oldState != null) {
			Closer.tryClose(oldState);
		}

		hmap.clear();
		tree.setModel(new DefaultTreeModel(null));
		open = false;
		metadataSystem = new MetadataSystem(typeLoader);
	}

	public void changeTheme(String xml) {
		InputStream in = getClass().getResourceAsStream(LuytenPreferences.THEME_XML_PATH + xml);
		try {
			if (in != null) {
				theme = Theme.load(in);
				for (OpenFile f : hmap) {
					theme.apply(f.textArea);
				}
			}
		} catch (Exception e1) {
			e1.printStackTrace();
			JOptionPane.showMessageDialog(null, e1.toString(), "Error!", JOptionPane.ERROR_MESSAGE);
		}
	}

	public File getOpenedFile() {
		File openedFile = null;
		if (file != null && open) {
			openedFile = file;
		}
		if (openedFile == null) {
			label.setText("No open file");
		}
		return openedFile;
	}

	public String getCurrentTabTitle() {
		String tabTitle = null;
		try {
			int pos = house.getSelectedIndex();
			if (pos >= 0) {
				tabTitle = house.getTitleAt(pos);
			}
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		if (tabTitle == null) {
			label.setText("No open tab");
		}
		return tabTitle;
	}
	
	public RSyntaxTextArea getCurrentTextArea() {
		RSyntaxTextArea currentTextArea = null;
		try {
			int pos = house.getSelectedIndex();
			if (pos >= 0) {
				RTextScrollPane co = (RTextScrollPane) house.getComponentAt(pos);
				currentTextArea = (RSyntaxTextArea) co.getViewport().getView();
			}
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		if (currentTextArea == null) {
			label.setText("No open tab");
		}
		return currentTextArea;
	}
}
