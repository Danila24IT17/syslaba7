import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class Main extends JFrame {
    private final JTextField urlField = new JTextField();
    private final JButton downloadButton = new JButton("Скачать");
    private final JButton stopButton = new JButton("Отмена");
    private final JLabel status = new JLabel("Вставьте прямую ссылку на картинку");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final DefaultListModel<Path> images = new DefaultListModel<>();
    private final JList<Path> imageList = new JList<>(images);
    private final Preview preview = new Preview();
    private final JLabel folderLabel = new JLabel();
    private Path folder = Paths.get(System.getProperty("user.home"), "Pictures", "labar_7");
    private DownloadTask task;

    public Main() {
        super("Л/р 7 — Качаем картинки");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(940, 620);
        setMinimumSize(new Dimension(760, 480));
        setLocationRelativeTo(null);

        JPanel left = new JPanel(new BorderLayout(8, 8));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JPanel input = new JPanel(new BorderLayout(8, 0));
        input.add(urlField, BorderLayout.CENTER);
        input.add(downloadButton, BorderLayout.EAST);
        urlField.setToolTipText("Прямая HTTP/HTTPS-ссылка на PNG, JPEG, GIF или BMP");
        controls.add(new JLabel("Ссылка на изображение:"));
        controls.add(Box.createVerticalStrut(5));
        controls.add(input);
        controls.add(Box.createVerticalStrut(10));
        controls.add(status);
        controls.add(Box.createVerticalStrut(7));
        progress.setStringPainted(true);
        controls.add(progress);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton previous = new JButton("Предыдущая");
        JButton next = new JButton("Следующая");
        navigation.add(stopButton);
        navigation.add(previous);
        navigation.add(next);
        controls.add(navigation);
        left.add(controls, BorderLayout.NORTH);
        preview.setBorder(BorderFactory.createTitledBorder("Предпросмотр"));
        left.add(preview, BorderLayout.CENTER);
        JButton chooseFolder = new JButton("Папка сохранения…");
        JPanel destination = new JPanel(new BorderLayout(5, 5));
        destination.add(chooseFolder, BorderLayout.NORTH);
        destination.add(folderLabel, BorderLayout.SOUTH);
        left.add(destination, BorderLayout.SOUTH);
        updateFolderLabel();

        JPanel right = new JPanel(new BorderLayout(6, 6));
        right.add(new JLabel("Скачанные изображения"), BorderLayout.NORTH);
        imageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        imageList.setFixedCellHeight(30);
        imageList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                Path path = (Path) value;
                label.setText(path.getFileName().toString());
                label.setToolTipText(path.toString());
                return label;
            }
        });
        right.add(new JScrollPane(imageList), BorderLayout.CENTER);
        JButton open = new JButton("Открыть");
        JButton remove = new JButton("Убрать из списка");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(open);
        actions.add(remove);
        right.add(actions, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.62);
        split.setDividerLocation(550);
        split.setBorder(new EmptyBorder(12, 12, 12, 12));
        add(split);

        stopButton.setEnabled(false);
        downloadButton.addActionListener(e -> startDownload());
        urlField.addActionListener(e -> startDownload());
        stopButton.addActionListener(e -> { if (task != null) task.cancel(true); });
        previous.addActionListener(e -> selectRelative(-1));
        next.addActionListener(e -> selectRelative(1));
        chooseFolder.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(folder.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folder = chooser.getSelectedFile().toPath();
                updateFolderLabel();
            }
        });
        remove.addActionListener(e -> {
            int index = imageList.getSelectedIndex();
            if (index >= 0) images.remove(index); // Файл на диске остаётся.
        });
        open.addActionListener(e -> {
            Path path = imageList.getSelectedValue();
            if (path != null) {
                try { Desktop.getDesktop().open(path.toFile()); }
                catch (Exception ex) { showError("Не удалось открыть файл: " + ex.getMessage()); }
            }
        });
        imageList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelectedImage();
        });
    }

    private void updateFolderLabel() {
        folderLabel.setText(folder.toString());
        folderLabel.setToolTipText(folder.toString());
    }

    private void selectRelative(int step) {
        if (images.isEmpty()) return;
        int index = imageList.getSelectedIndex();
        imageList.setSelectedIndex(index < 0 ? 0 : Math.floorMod(index + step, images.size()));
        imageList.ensureIndexIsVisible(imageList.getSelectedIndex());
    }

    private void showSelectedImage() {
        Path selected = imageList.getSelectedValue();
        if (selected == null) { preview.setImage(null); return; }
        new SwingWorker<BufferedImage, Void>() {
            protected BufferedImage doInBackground() throws IOException {
                return ImageIO.read(selected.toFile());
            }
            protected void done() {
                if (!selected.equals(imageList.getSelectedValue())) return;
                try { preview.setImage(get()); }
                catch (Exception ex) { preview.setImage(null); showError("Не удалось прочитать изображение."); }
            }
        }.execute();
    }

    private void startDownload() {
        if (task != null) return;
        URI uri;
        try {
            uri = new URI(urlField.getText().trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (Exception ex) {
            showError("Введите корректную прямую ссылку, начинающуюся с http:// или https://");
            return;
        }
        downloadButton.setEnabled(false);
        stopButton.setEnabled(true);
        progress.setValue(0);
        progress.setIndeterminate(true);
        status.setText("Подключение…");
        task = new DownloadTask(uri, folder);
        task.execute();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private class DownloadTask extends SwingWorker<Path, Integer> {
        private final URI uri;
        private final Path destination;
        DownloadTask(URI uri, Path destination) { this.uri = uri; this.destination = destination; }

        @Override protected Path doInBackground() throws Exception {
            Files.createDirectories(destination);
            Path temp = Files.createTempFile(destination, ".download-", ".part");
            HttpURLConnection connection = null;
            try {
                URL current = uri.toURL();
                for (int redirects = 0; ; redirects++) {
                    if (isCancelled()) throw new CancellationException();
                    connection = (HttpURLConnection) current.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "ImageDownloader-Lab7/1.0");
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (redirects >= 5 || location == null) throw new IOException("Слишком много перенаправлений или отсутствует адрес.");
                        URL redirected = new URL(current, location);
                        if (!redirected.getProtocol().equals("http") && !redirected.getProtocol().equals("https"))
                            throw new IOException("Недопустимый протокол перенаправления.");
                        connection.disconnect();
                        current = redirected;
                    } else {
                        if (code != 200) throw new IOException("Сервер вернул HTTP " + code);
                        break;
                    }
                }
                long total = connection.getContentLengthLong();
                long received = 0;
                final long limit = 30L * 1024 * 1024;
                if (total > limit) throw new IOException("Размер изображения превышает 30 МБ.");
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (isCancelled()) throw new CancellationException();
                        received += count;
                        if (received > limit) throw new IOException("Размер изображения превышает 30 МБ.");
                        output.write(buffer, 0, count);
                        publish(total > 0 ? (int) Math.min(99, received * 100 / total) : -1);
                    }
                }
                if (total >= 0 && received != total) throw new IOException("Соединение прервано: файл получен не полностью.");
                String extension;
                try (var stream = ImageIO.createImageInputStream(temp.toFile())) {
                    var readers = ImageIO.getImageReaders(stream);
                    if (!readers.hasNext()) throw new IOException("По ссылке нет поддерживаемой картинки. Используйте PNG, JPEG, GIF или BMP.");
                    var reader = readers.next();
                    try {
                        reader.setInput(stream);
                        if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000)
                            throw new IOException("Слишком большое разрешение изображения (максимум 40 мегапикселей).");
                        extension = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                        reader.read(0);
                    } finally { reader.dispose(); }
                }
                if (isCancelled()) throw new CancellationException();
                Path saved = Files.createTempFile(destination, "image-", "." + extension);
                try { Files.move(temp, saved, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ex) { Files.deleteIfExists(saved); throw ex; }
                if (isCancelled()) { Files.deleteIfExists(saved); throw new CancellationException(); }
                return saved;
            } finally {
                if (connection != null) connection.disconnect();
                Files.deleteIfExists(temp);
            }
        }

        @Override protected void process(List<Integer> chunks) {
            if (isCancelled()) return;
            int value = chunks.get(chunks.size() - 1);
            progress.setIndeterminate(value < 0);
            if (value >= 0) progress.setValue(value);
            status.setText("Подождите, идёт скачивание…");
        }

        @Override protected void done() {
            progress.setIndeterminate(false);
            downloadButton.setEnabled(true);
            stopButton.setEnabled(false);
            task = null;
            try {
                Path saved = get();
                images.addElement(saved);
                imageList.setSelectedValue(saved, true);
                progress.setValue(100);
                status.setText("Готово! Изображение сохранено.");
            } catch (CancellationException ex) {
                progress.setValue(0);
                status.setText("Скачивание отменено.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                progress.setValue(0);
                status.setText("Не удалось скачать изображение.");
                showError(ex.getCause().getMessage());
            }
        }
    }

    private static class Preview extends JPanel {
        private BufferedImage image;
        Preview() { setBackground(Color.WHITE); }
        void setImage(BufferedImage image) { this.image = image; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                graphics.setColor(Color.GRAY);
                graphics.drawString("Здесь появится скачанная картинка", 24, getHeight() / 2);
                return;
            }
            double scale = Math.min(1.0, Math.min((getWidth() - 24.0) / image.getWidth(),
                    (getHeight() - 40.0) / image.getHeight()));
            int width = Math.max(1, (int) (image.getWidth() * scale));
            int height = Math.max(1, (int) (image.getHeight() * scale));
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            g.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Используем стандартный стиль Swing. */ }
            new Main().setVisible(true);
        });
    }
}
