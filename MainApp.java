// MainApp.java
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/*
 Single-file Stock Market Simulator
 - Compile: javac MainApp.java
 - Run:     java MainApp
 - Data files (auto-created) stored under ./data/
*/

public class MainApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Utils.ensureDataDir();
            MarketEngine engine = new MarketEngine();
            Controller controller = new Controller(engine);

            LoginDialog login = new LoginDialog(controller);
            login.setVisible(true);
            if (!login.isSucceeded()) {
                System.exit(0);
            }

            // show main UI
            DarkView view = new DarkView(controller);
            view.setVisible(true);
        });
    }
}

/* ----------------------- Utils ----------------------- */
class Utils {
    public static final String DATA_DIR = "data";
    public static final String USERS_FILE = DATA_DIR + File.separator + "users.csv";

    public static void ensureDataDir() {
        File d = new File(DATA_DIR);
        if (!d.exists()) d.mkdirs();
    }

    public static List<String[]> readCSV(String path) {
        List<String[]> out = new ArrayList<>();
        File f = new File(path);
        if (!f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line.split(",", -1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return out;
    }

    public static void appendLine(String path, String line) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, true))) {
            pw.println(line);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void writeAll(String path, List<String> lines) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, false))) {
            for (String l : lines) pw.println(l);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // users
    public static Map<String, String[]> readUsersMap() {
        ensureDataDir();
        Map<String, String[]> map = new LinkedHashMap<>();
        File f = new File(USERS_FILE);
        if (!f.exists()) return map;
        List<String[]> rows = readCSV(USERS_FILE);
        for (String[] r : rows) {
            if (r.length >= 3) map.put(r[0], new String[]{r[1], r[2]});
        }
        return map;
    }

    public static void writeUsersMap(Map<String, String[]> map) {
        ensureDataDir();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String[]> e : map.entrySet()) {
            lines.add(e.getKey() + "," + e.getValue()[0] + "," + e.getValue()[1]);
        }
        writeAll(USERS_FILE, lines);
    }

    // portfolio: data/portfolio_<username>.csv -> symbol,qty,avgPrice
    public static void savePortfolio(String username, Collection<PortfolioItem> items) {
        ensureDataDir();
        String path = DATA_DIR + File.separator + "portfolio_" + username + ".csv";
        List<String> lines = new ArrayList<>();
        for (PortfolioItem it : items) {
            lines.add(it.getSymbol() + "," + it.getQuantity() + "," + String.format("%.2f", it.getAvgPrice()));
        }
        writeAll(path, lines);
    }

    public static List<String[]> loadPortfolio(String username) {
        ensureDataDir();
        String path = DATA_DIR + File.separator + "portfolio_" + username + ".csv";
        return readCSV(path);
    }

    // transactions: data/tx_<username>.csv -> type,symbol,qty,price,timestamp
    public static void appendTransaction(String username, Transaction tx) {
        ensureDataDir();
        String path = DATA_DIR + File.separator + "tx_" + username + ".csv";
        appendLine(path, tx.getType() + "," + tx.getSymbol() + "," + tx.getQuantity() + "," + String.format("%.2f", tx.getPrice()) + "," + tx.getTimestamp());
    }

    public static List<String[]> loadTransactions(String username) {
        ensureDataDir();
        String path = DATA_DIR + File.separator + "tx_" + username + ".csv";
        return readCSV(path);
    }
}

/* ----------------------- Models ----------------------- */
class Stock {
    private final String name;
    private final String symbol;
    private double price;
    private double lastPrice;
    private final List<PricePoint> history = new ArrayList<>();

    public Stock(String name, String symbol, double price) {
        this.name = name;
        this.symbol = symbol;
        this.price = price;
        this.lastPrice = price;
        addHistory(price);
    }

    public synchronized String getName() { return name; }
    public synchronized String getSymbol() { return symbol; }
    public synchronized double getPrice() { return price; }
    public synchronized double getLastPrice() { return lastPrice; }

    public synchronized void setPrice(double p) {
        lastPrice = price;
        price = Math.max(0.01, Math.round(p * 100.0) / 100.0);
        addHistory(price);
    }

    private void addHistory(double p) {
        history.add(new PricePoint(LocalDateTime.now(), p));
        if (history.size() > 200) history.remove(0);
    }

    public synchronized List<PricePoint> getHistory() { return new ArrayList<>(history); }

    public synchronized double getDailyChangePercent() {
        if (history.size() < 2) return 0.0;
        double first = history.get(0).price;
        if (first <= 0) return 0.0;
        return (price - first) / first * 100.0;
    }

    static class PricePoint {
        final LocalDateTime time;
        final double price;
        PricePoint(LocalDateTime t, double p) { time = t; price = p; }
    }
}

class PortfolioItem {
    private final String symbol;
    private int quantity;
    private double avgPrice;

    public PortfolioItem(String symbol, int quantity, double avgPrice) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
    }

    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getAvgPrice() { return avgPrice; }

    public void addQuantity(int q, double pricePerShare) {
        double totalCost = avgPrice * quantity + pricePerShare * q;
        quantity += q;
        avgPrice = (quantity == 0) ? 0 : totalCost / quantity;
    }

    public boolean removeQuantity(int q) {
        if (q > quantity) return false;
        quantity -= q;
        return true;
    }
}

class Transaction {
    private final String type; // BUY / SELL
    private final String symbol;
    private final int quantity;
    private final double price;
    private final String timestamp;

    public Transaction(String type, String symbol, int quantity, double price) {
        this.type = type;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getType() { return type; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public String getTimestamp() { return timestamp; }
}

class User {
    private final String username;
    private final String password;
    private double balance;

    public User(String username, String password, double balance) {
        this.username = username;
        this.password = password;
        this.balance = balance;
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }

    public synchronized double getBalance() { return balance; }
    public synchronized void deposit(double amt) { balance += amt; }
    public synchronized boolean withdraw(double amt) {
        if (amt <= balance) { balance -= amt; return true; }
        return false;
    }
}

/* ----------------------- Market Engine ----------------------- */
class MarketEngine {
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;
    private final Random rng = new Random();

    public MarketEngine() {
        // --- Preloaded Stock List (Indian + Global) ---
        addStock(new Stock("Reliance Industries", "RELI", 2850.00));
        addStock(new Stock("TCS", "TCS", 3450.00));
        addStock(new Stock("Infosys", "INFY", 1450.50));
        addStock(new Stock("HCL Technologies", "HCLT", 1120.40));
        addStock(new Stock("Wipro", "WIPRO", 490.20));
        addStock(new Stock("Maruti Suzuki", "MARUTI", 10725.50));
        addStock(new Stock("Tata Motors", "TATAMOT", 925.10));
        addStock(new Stock("HDFC Bank", "HDFCBANK", 1590.45));
        addStock(new Stock("ICICI Bank", "ICICIBANK", 1045.35));
        addStock(new Stock("State Bank of India", "SBIN", 845.25));
        addStock(new Stock("Bajaj Finance", "BAJFIN", 6980.75));
        addStock(new Stock("Asian Paints", "ASIANPNT", 3200.10));
        addStock(new Stock("ITC Ltd", "ITC", 470.35));
        addStock(new Stock("Adani Enterprises", "ADANIENT", 2325.60));
        addStock(new Stock("Larsen & Toubro", "LT", 3580.90));
        addStock(new Stock("Bharti Airtel", "AIRTEL", 1030.25));
        addStock(new Stock("Sun Pharma", "SUNPHARMA", 1455.15));
        addStock(new Stock("Titan Company", "TITAN", 3450.80));
        addStock(new Stock("Nestle India", "NESTLE", 25900.00));
        addStock(new Stock("Hindustan Unilever", "HUL", 2530.25));
        addStock(new Stock("PowerGrid Corp", "POWERGRID", 310.10));
        addStock(new Stock("ONGC", "ONGC", 265.45));
        addStock(new Stock("Coal India", "COALIND", 410.60));
        addStock(new Stock("Adani Green", "ADANIGRN", 1210.75));
        addStock(new Stock("JSW Steel", "JSWSTL", 930.25));
        addStock(new Stock("NTPC", "NTPC", 310.40));
        addStock(new Stock("Apple Inc.", "AAPL", 150.00));
        addStock(new Stock("Microsoft Corp", "MSFT", 340.00));
        addStock(new Stock("Amazon", "AMZN", 130.00));
        addStock(new Stock("Tesla Motors", "TSLA", 220.00));
        addStock(new Stock("Google (Alphabet)", "GOOG", 125.00));
        addStock(new Stock("Meta Platforms", "META", 250.00));
        addStock(new Stock("NVIDIA Corp", "NVDA", 900.00));
        addStock(new Stock("Adobe Inc.", "ADBE", 510.00));
        addStock(new Stock("Intel Corp", "INTC", 34.00));
        addStock(new Stock("Oracle", "ORCL", 108.00));
        addStock(new Stock("Coca-Cola", "KO", 58.00));
        addStock(new Stock("PepsiCo", "PEP", 175.00));
        addStock(new Stock("Toyota Motor", "TM", 190.00));
        addStock(new Stock("Sony Group", "SONY", 88.00));
    }

    private void addStock(Stock s) {
        stocks.put(s.getSymbol(), s);
    }

    public Collection<Stock> getStocks() {
        return stocks.values();
    }

    public Stock getStock(String symbol) {
        return stocks.get(symbol);
    }

    /** Start market price simulation and call the given Runnable after each tick */
    public void start(Runnable onTick) {
        if (scheduler != null && !scheduler.isShutdown()) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            simulateTick();
            if (onTick != null) {
                try { onTick.run(); } catch (Exception ignored) {}
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Simulate ±2% change, occasional random spike */
    private void simulateTick() {
        for (Stock s : stocks.values()) {
            double price = s.getPrice();
            double pctChange = (rng.nextDouble() * 4.0) - 2.0; // -2% to +2%
            double newPrice = price + price * pctChange / 100.0;
            if (rng.nextDouble() < 0.03) {
                newPrice *= (1 + (rng.nextDouble() * 0.08 - 0.04)); // random spike
            }
            s.setPrice(newPrice);
        }
    }
}

/* ----------------------- Controller ----------------------- */
class Controller {
    private final MarketEngine engine;
    private User currentUser;
    private final Map<String, PortfolioItem> portfolioMap = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public Controller(MarketEngine engine) {
        this.engine = engine;
        Utils.ensureDataDir();
    }

    public MarketEngine getEngine() { return engine; }
    public User getCurrentUser() { return currentUser; }
    public Collection<PortfolioItem> getPortfolioItems() { return new ArrayList<>(portfolioMap.values()); }
    public List<Transaction> getTransactions() { return new ArrayList<>(transactions); }

    // Sign up
    public boolean signup(String username, String password) {
        Map<String, String[]> users = Utils.readUsersMap();
        if (users.containsKey(username) || username.isBlank()) return false;
        double initial = 10000.0;
        users.put(username, new String[]{password, String.format("%.2f", initial)});
        Utils.writeUsersMap(users);
        return true;
    }

    // Login
    public boolean login(String username, String password) {
        Map<String, String[]> users = Utils.readUsersMap();
        if (!users.containsKey(username)) return false;
        String[] v = users.get(username);
        if (!v[0].equals(password)) return false;
        double bal = 10000.0;
        try { bal = Double.parseDouble(v[1]); } catch (Exception ignored) {}
        currentUser = new User(username, password, bal);
        loadPortfolio();
        loadTransactions();
        return true;
    }

    private void loadPortfolio() {
        portfolioMap.clear();
        List<String[]> rows = Utils.loadPortfolio(currentUser.getUsername());
        for (String[] r : rows) {
            try {
                String sym = r[0];
                int qty = Integer.parseInt(r[1]);
                double avg = Double.parseDouble(r[2]);
                portfolioMap.put(sym, new PortfolioItem(sym, qty, avg));
            } catch (Exception ignored) {}
        }
    }

    private void savePortfolio() {
        Utils.savePortfolio(currentUser.getUsername(), portfolioMap.values());
    }

    private void loadTransactions() {
        transactions.clear();
        List<String[]> rows = Utils.loadTransactions(currentUser.getUsername());
        for (String[] r : rows) {
            try {
                String type = r[0];
                String sym = r[1];
                int qty = Integer.parseInt(r[2]);
                double price = Double.parseDouble(r[3]);
                // timestamp r[4] exists in CSV but constructor sets now; ok for display
                Transaction t = new Transaction(type, sym, qty, price);
                transactions.add(0, t);
            } catch (Exception ignored) {}
        }
    }

    private void persistUsersFile() {
        Map<String, String[]> users = Utils.readUsersMap();
        users.put(currentUser.getUsername(), new String[]{currentUser.getPassword(), String.format("%.2f", currentUser.getBalance())});
        Utils.writeUsersMap(users);
    }

    // Buy
    public void buy(String symbol, int qty, Runnable callbackOnFinish) {
        if (currentUser == null) return;
        Stock s = engine.getStock(symbol);
        if (s == null) return;
        double cost = s.getPrice() * qty;
        boolean ok;
        synchronized (currentUser) { ok = currentUser.withdraw(cost); }
        if (!ok) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, "Insufficient balance.");
                if (callbackOnFinish != null) callbackOnFinish.run();
            });
            return;
        }
        PortfolioItem it = portfolioMap.get(symbol);
        if (it == null) {
            it = new PortfolioItem(symbol, qty, s.getPrice());
            portfolioMap.put(symbol, it);
        } else {
            it.addQuantity(qty, s.getPrice());
        }
        Transaction tx = new Transaction("BUY", symbol, qty, s.getPrice());
        transactions.add(0, tx);
        Utils.appendTransaction(currentUser.getUsername(), tx);
        savePortfolio();
        persistUsersFile();
        SwingUtilities.invokeLater(() -> { if (callbackOnFinish != null) callbackOnFinish.run(); });
    }

    // Sell
    public void sell(String symbol, int qty, Runnable callbackOnFinish) {
        if (currentUser == null) return;
        Stock s = engine.getStock(symbol);
        if (s == null) return;
        PortfolioItem it = portfolioMap.get(symbol);
        if (it == null || it.getQuantity() < qty) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, "Not enough shares to sell.");
                if (callbackOnFinish != null) callbackOnFinish.run();
            });
            return;
        }
        it.removeQuantity(qty);
        if (it.getQuantity() == 0) portfolioMap.remove(symbol);
        double gain = s.getPrice() * qty;
        synchronized (currentUser) { currentUser.deposit(gain); }
        Transaction tx = new Transaction("SELL", symbol, qty, s.getPrice());
        transactions.add(0, tx);
        Utils.appendTransaction(currentUser.getUsername(), tx);
        savePortfolio();
        persistUsersFile();
        SwingUtilities.invokeLater(() -> { if (callbackOnFinish != null) callbackOnFinish.run(); });
    }
}

/* ----------------------- LoginDialog ----------------------- */
class LoginDialog extends JDialog {
    private boolean succeeded = false;
    public LoginDialog(Controller controller) {
        setModal(true);
        setTitle("Login / Signup");
        setSize(420,240);
        setLocationRelativeTo(null);
        setLayout(null);
        getContentPane().setBackground(new Color(18,20,22));

        JLabel l1 = new JLabel("Username:"); l1.setForeground(Color.WHITE); l1.setBounds(28,22,100,24); add(l1);
        JTextField tfUser = new JTextField(); tfUser.setBounds(28,48,360,30); add(tfUser);

        JLabel l2 = new JLabel("Password:"); l2.setForeground(Color.WHITE); l2.setBounds(28,86,100,24); add(l2);
        JPasswordField pf = new JPasswordField(); pf.setBounds(28,112,360,30); add(pf);

        JButton btnLogin = new JButton("Login"); btnLogin.setBounds(28,154,160,34); add(btnLogin);
        JButton btnSignup = new JButton("Signup"); btnSignup.setBounds(228,154,160,34); add(btnSignup);

        btnLogin.addActionListener(e -> {
            String u = tfUser.getText().trim(), p = new String(pf.getPassword()).trim();
            if (controller.login(u, p)) { succeeded = true; dispose(); }
            else JOptionPane.showMessageDialog(this, "Invalid credentials");
        });

        btnSignup.addActionListener(e -> {
            String u = tfUser.getText().trim(), p = new String(pf.getPassword()).trim();
            if (u.isBlank() || p.isBlank()) { JOptionPane.showMessageDialog(this, "Enter username & password"); return; }
            boolean ok = controller.signup(u, p);
            if (ok) JOptionPane.showMessageDialog(this, "Signup successful. Login now."); else JOptionPane.showMessageDialog(this, "User exists or invalid.");
        });
    }
    public boolean isSucceeded() { return succeeded; }
}

/* ----------------------- DarkView (Main UI) ----------------------- */
class DarkView extends JFrame {
    private final Controller controller;
    private final MarketEngine engine;
    private final DecimalFormat df = new DecimalFormat("#.##");

    private final DefaultTableModel marketModel;
    private final JTable marketTable;
    private final DefaultTableModel portfolioModel;
    private final JTable portfolioTable;
    private final DefaultTableModel txModel;
    private final JTable txTable;
    private final JLabel balanceLabel;
    private final ChartCanvas chartCanvas;

    public DarkView(Controller controller) {
        this.controller = controller;
        this.engine = controller.getEngine();

        setTitle("Dark Stock Market Simulator");
        setSize(1200, 740);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(new Color(24, 28, 33));
        setLayout(new BorderLayout());

        // Top bar
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(34, 38, 44));
        top.setBorder(new EmptyBorder(10,12,10,12));
        JLabel title = new JLabel("Quantum Trader - Simulator");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        top.add(title, BorderLayout.WEST);
        balanceLabel = new JLabel("Balance: ₹0.00");
        balanceLabel.setForeground(Color.WHITE);
        top.add(balanceLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // Left nav
        JPanel left = new JPanel();
        left.setBackground(new Color(20,24,28));
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(new EmptyBorder(12,12,12,12));
        JButton b1 = makeNavButton("Portfolio");
        JButton b2 = makeNavButton("Live Chart");
        JButton b3 = makeNavButton("Trade");
        left.add(b1); left.add(Box.createVerticalStrut(8));
        left.add(b2); left.add(Box.createVerticalStrut(8));
        left.add(b3);
        add(left, BorderLayout.WEST);

        // Center cards
        CardLayout cl = new CardLayout();
        JPanel centerCards = new JPanel(cl);
        centerCards.setBackground(new Color(24,28,33));

        // Portfolio panel
        JPanel portPanel = new JPanel(new BorderLayout());
        portPanel.setBackground(new Color(24,28,33));
        portfolioModel = new DefaultTableModel(new String[]{"Symbol","Qty","Avg Price (₹)","Cur Price","Value (₹)","P/L (₹)"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        portfolioTable = new JTable(portfolioModel);
        styleTable(portfolioTable);
        portPanel.add(new JScrollPane(portfolioTable), BorderLayout.CENTER);

        // Chart panel
        JPanel chartPanel = new JPanel(new BorderLayout());
        chartPanel.setBackground(new Color(24,28,33));
        chartCanvas = new ChartCanvas();
        chartPanel.add(chartCanvas, BorderLayout.CENTER);

        // Trade panel
        JPanel tradePanel = new JPanel();
        tradePanel.setBackground(new Color(24,28,33));
        tradePanel.setLayout(new BoxLayout(tradePanel, BoxLayout.Y_AXIS));
        tradePanel.setBorder(new EmptyBorder(12,12,12,12));
        JTextField symField = new JTextField(); symField.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));
        JTextField qtyField = new JTextField(); qtyField.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));
        JButton buyBtn = new JButton("BUY"); buyBtn.setBackground(new Color(24,160,80)); buyBtn.setForeground(Color.BLACK);
        JButton sellBtn = new JButton("SELL"); sellBtn.setBackground(new Color(220,60,60)); sellBtn.setForeground(Color.WHITE);
        tradePanel.add(new JLabel("Symbol (e.g. ACME)")); tradePanel.add(symField);
        tradePanel.add(Box.createVerticalStrut(8));
        tradePanel.add(new JLabel("Quantity")); tradePanel.add(qtyField);
        tradePanel.add(Box.createVerticalStrut(12));
        JPanel tradeButtons = new JPanel(); tradeButtons.setBackground(new Color(24,28,33));
        tradeButtons.add(buyBtn); tradeButtons.add(sellBtn);
        tradePanel.add(tradeButtons);

        centerCards.add(portPanel, "portfolio");
        centerCards.add(chartPanel, "charts");
        centerCards.add(tradePanel, "trade");
        add(centerCards, BorderLayout.CENTER);

        // Right: market + transactions
        JPanel right = new JPanel(new BorderLayout(8,8));
        right.setBackground(new Color(24,28,33));
        right.setPreferredSize(new Dimension(420,0));

        marketModel = new DefaultTableModel(new String[]{"Name","Symbol","Price (₹)","Δ (%)"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        marketTable = new JTable(marketModel);
        styleTable(marketTable);
        marketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        right.add(new JScrollPane(marketTable), BorderLayout.CENTER);

        txModel = new DefaultTableModel(new String[]{"Type","Symbol","Qty","Price","Time"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        txTable = new JTable(txModel);
        styleTable(txTable);
        txTable.setRowHeight(22);
        JScrollPane txScroll = new JScrollPane(txTable);
        txScroll.setPreferredSize(new Dimension(420, 180));
        right.add(txScroll, BorderLayout.SOUTH);

        add(right, BorderLayout.EAST);

        // actions
        b1.addActionListener(e -> cl.show(centerCards, "portfolio"));
        b2.addActionListener(e -> cl.show(centerCards, "charts"));
        b3.addActionListener(e -> cl.show(centerCards, "trade"));

        marketTable.getSelectionModel().addListSelectionListener(e -> {
            int r = marketTable.getSelectedRow();
            if (r >= 0) {
                String symbol = (String) marketModel.getValueAt(r,1);
                Stock s = engine.getStock(symbol);
                if (s != null) chartCanvas.setStock(s);
                symField.setText(symbol);
            }
        });

        buyBtn.addActionListener(e -> {
            String symbol = symField.getText().trim();
            String q = qtyField.getText().trim();
            if (symbol.isEmpty() || q.isEmpty()) { JOptionPane.showMessageDialog(this, "Symbol and quantity required"); return; }
            try {
                int qty = Integer.parseInt(q);
                controller.buy(symbol, qty, () -> {
                    refreshAll();
                    JOptionPane.showMessageDialog(this, "Bought " + qty + " x " + symbol);
                });
            } catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Invalid quantity"); }
        });

        sellBtn.addActionListener(e -> {
            String symbol = symField.getText().trim();
            String q = qtyField.getText().trim();
            if (symbol.isEmpty() || q.isEmpty()) { JOptionPane.showMessageDialog(this, "Symbol and quantity required"); return; }
            try {
                int qty = Integer.parseInt(q);
                controller.sell(symbol, qty, () -> {
                    refreshAll();
                    JOptionPane.showMessageDialog(this, "Sold " + qty + " x " + symbol);
                });
            } catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Invalid quantity"); }
        });

        // start engine updates and UI refresh
        engine.start(() -> SwingUtilities.invokeLater(this::refreshAll));
        refreshAll();
    }

    private JButton makeNavButton(String text) {
        JButton b = new JButton(text);
        b.setMaximumSize(new Dimension(220,44));
        b.setBackground(new Color(34,38,44));
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setFocusPainted(false);
        return b;
    }

    private void styleTable(JTable t) {
        t.setBackground(new Color(34,38,44));
        t.setForeground(Color.WHITE);
        t.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        t.getTableHeader().setBackground(new Color(24,28,33));
        t.getTableHeader().setForeground(Color.WHITE);
    }

    public void refreshAll() {
        // update balance label from controller user
        User u = controller.getCurrentUser();
        if (u != null) balanceLabel.setText("Balance: ₹" + df.format(u.getBalance()));
        else balanceLabel.setText("Balance: ₹0.00");

        // market table
        marketModel.setRowCount(0);
        for (Stock s : engine.getStocks()) {
            marketModel.addRow(new Object[]{s.getName(), s.getSymbol(), s.getPrice(), String.format("%.2f%%", s.getDailyChangePercent())});
        }

        // portfolio
        portfolioModel.setRowCount(0);
        for (PortfolioItem it : controller.getPortfolioItems()) {
            Stock s = engine.getStock(it.getSymbol());
            double cur = (s == null) ? 0.0 : s.getPrice();
            double val = cur * it.getQuantity();
            double pl = (cur - it.getAvgPrice()) * it.getQuantity();
            portfolioModel.addRow(new Object[]{it.getSymbol(), it.getQuantity(), String.format("%.2f", it.getAvgPrice()), String.format("%.2f", cur), String.format("%.2f", val), String.format("%.2f", pl)});
        }

        // transactions
        txModel.setRowCount(0);
        for (Transaction t : controller.getTransactions()) {
            txModel.addRow(new Object[]{t.getType(), t.getSymbol(), t.getQuantity(), String.format("%.2f", t.getPrice()), t.getTimestamp()});
        }

        chartCanvas.repaint();
    }

    /* Lightweight chart canvas */
    private static class ChartCanvas extends JPanel {
        private Stock stock;
        ChartCanvas() {
            setBackground(new Color(18,20,22));
            setPreferredSize(new Dimension(600,420));
            setBorder(new EmptyBorder(12,12,12,12));
        }
        public void setStock(Stock s) { this.stock = s; repaint(); }
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (stock == null) return;
            List<Stock.PricePoint> h = stock.getHistory();
            if (h.size() < 2) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), hgt = getHeight();
            int margin = 30;
            int gw = w - 2*margin, gh = hgt - 2*margin;
            double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
            for (Stock.PricePoint p : h) { min = Math.min(min, p.price); max = Math.max(max, p.price); }
            if (min == max) { min -= 1; max += 1; }
            int n = h.size();
            int[] xs = new int[n], ys = new int[n];
            for (int i=0;i<n;i++) {
                xs[i] = margin + (int)((double)i/(n-1) * gw);
                ys[i] = margin + gh - (int)((h.get(i).price - min)/(max-min) * gh);
            }
            // area fill
            g.setColor(new Color(30,80,50,60));
            for (int i=0;i<n-1;i++) {
                int[] px = {xs[i], xs[i+1], xs[i+1], xs[i]};
                int[] py = {ys[i], ys[i+1], margin+gh, margin+gh};
                g.fillPolygon(px, py, 4);
            }
            // line
            g.setColor(new Color(0,180,100));
            g.setStroke(new BasicStroke(2.5f));
            for (int i=0;i<n-1;i++) g.drawLine(xs[i], ys[i], xs[i+1], ys[i+1]);
            // labels
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g.drawString(stock.getSymbol() + "  ₹" + String.format("%.2f", stock.getPrice()), margin+6, margin+14);
        }
    }
}
