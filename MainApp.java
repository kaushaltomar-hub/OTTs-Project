// MainApp.java
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/*
 Single-file Stock Market Simulator with TradingView-Style Charts
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
        // Keep 100 points for a cleaner chart view
        if (history.size() > 100) history.remove(0);
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
        // --- Preloaded Stock List ---
 addStock(new Stock("Reliance Industries", "RELI", 2964.42));
        addStock(new Stock("TCS", "TCS", 3469.68));
        addStock(new Stock("HDFC Bank", "HDFCBANK", 1632.54));
        addStock(new Stock("Infosys", "INFY", 1426.57));
        addStock(new Stock("ICICI Bank", "ICICIBANK", 1045.35));
        addStock(new Stock("Hindustan Unilever", "HUL", 2530.25));
        addStock(new Stock("ITC Ltd", "ITC", 470.35));
        addStock(new Stock("State Bank of India", "SBIN", 845.25));
        addStock(new Stock("Bharti Airtel", "AIRTEL", 1130.25));
        addStock(new Stock("Larsen & Toubro", "LT", 3580.90));
        addStock(new Stock("Bajaj Finance", "BAJFIN", 6980.75));
        addStock(new Stock("Maruti Suzuki", "MARUTI", 10807.42));
        addStock(new Stock("Titan Company", "TITAN", 3602.59));
        addStock(new Stock("Sun Pharma", "SUNPHARMA", 1455.15));
        addStock(new Stock("Mahindra & Mahindra", "M&M", 1850.50));
        addStock(new Stock("Kotak Mahindra", "KOTAKBANK", 1750.10));
        addStock(new Stock("HCL Technologies", "HCLT", 1096.61));
        addStock(new Stock("Wipro", "WIPRO", 499.15));
        addStock(new Stock("Adani Enterprises", "ADANIENT", 2300.66));
        addStock(new Stock("Adani Green", "ADANIGRN", 1210.75));
        addStock(new Stock("Asian Paints", "ASIANPNT", 2950.10));
        addStock(new Stock("Tata Motors", "TATAMOT", 965.10));
        addStock(new Stock("Tata Steel", "TATASTEEL", 145.40));
        addStock(new Stock("PowerGrid Corp", "POWERGRID", 310.10));
        addStock(new Stock("NTPC", "NTPC", 310.40));
        addStock(new Stock("Zomato", "ZOMATO", 165.20));
        
        // --- US / GLOBAL TECH ---
        addStock(new Stock("Apple Inc.", "AAPL", 175.00));
        addStock(new Stock("Microsoft Corp", "MSFT", 420.00));
        addStock(new Stock("NVIDIA Corp", "NVDA", 929.35));
        addStock(new Stock("Amazon", "AMZN", 180.00));
        addStock(new Stock("Google (Alphabet)", "GOOGL", 165.00));
        addStock(new Stock("Meta Platforms", "META", 490.00));
        addStock(new Stock("Tesla Motors", "TSLA", 216.25));
        addStock(new Stock("Netflix", "NFLX", 610.00));
        addStock(new Stock("AMD", "AMD", 170.00));
        addStock(new Stock("Intel Corp", "INTC", 35.00));
        
        // --- CRYPTO ---
        addStock(new Stock("Bitcoin", "BTC", 64129.71));
        addStock(new Stock("Ethereum", "ETH", 3431.88));
        addStock(new Stock("Solana", "SOL", 145.50));
        addStock(new Stock("Binance Coin", "BNB", 590.20));
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

    /** Start market price simulation */
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
        }, 0, 1000, TimeUnit.MILLISECONDS); // Faster updates for smoother charts (1 sec)
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void simulateTick() {
        for (Stock s : stocks.values()) {
            double price = s.getPrice();
            // Smoother random walk
            double pctChange = (rng.nextGaussian() * 0.5); 
            double newPrice = price + price * pctChange / 100.0;
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
        getContentPane().setBackground(new Color(19, 23, 34)); // TradingView Dark BG
        setLayout(new BorderLayout());

        // Top bar
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(28, 32, 45));
        top.setBorder(new EmptyBorder(10,12,10,12));
        JLabel title = new JLabel("Quantum Trader");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        top.add(title, BorderLayout.WEST);
        balanceLabel = new JLabel("Balance: ₹0.00");
        balanceLabel.setForeground(new Color(0, 227, 150)); // Teal for money
        balanceLabel.setFont(new Font("Consolas", Font.BOLD, 16));
        top.add(balanceLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // Left nav
        JPanel left = new JPanel();
        left.setBackground(new Color(19, 23, 34));
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
        centerCards.setBackground(new Color(19, 23, 34));

        // Portfolio panel
        JPanel portPanel = new JPanel(new BorderLayout());
        portPanel.setBackground(new Color(19, 23, 34));
        portfolioModel = new DefaultTableModel(new String[]{"Symbol","Qty","Avg Price (₹)","Cur Price","Value (₹)","P/L (₹)"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        portfolioTable = new JTable(portfolioModel);
        styleTable(portfolioTable);
        portPanel.add(new JScrollPane(portfolioTable), BorderLayout.CENTER);

        // Chart panel (UPDATED TO LOOK LIKE TRADINGVIEW)
        JPanel chartPanel = new JPanel(new BorderLayout());
        chartPanel.setBackground(new Color(19, 23, 34));
        chartCanvas = new ChartCanvas();
        chartPanel.add(chartCanvas, BorderLayout.CENTER);

        // Trade panel
        JPanel tradePanel = new JPanel();
        tradePanel.setBackground(new Color(19, 23, 34));
        tradePanel.setLayout(new BoxLayout(tradePanel, BoxLayout.Y_AXIS));
        tradePanel.setBorder(new EmptyBorder(20,40,20,40));
        
        JLabel tradeTitle = new JLabel("Execute Order");
        tradeTitle.setForeground(Color.WHITE);
        tradeTitle.setFont(new Font("Segoe UI", Font.PLAIN, 24));
        tradeTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextField symField = new JTextField(); symField.setMaximumSize(new Dimension(Integer.MAX_VALUE,35));
        JTextField qtyField = new JTextField(); qtyField.setMaximumSize(new Dimension(Integer.MAX_VALUE,35));
        
        JButton buyBtn = new JButton("BUY"); 
        buyBtn.setBackground(new Color(0, 227, 150)); // Green
        buyBtn.setForeground(Color.BLACK);
        buyBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        
        JButton sellBtn = new JButton("SELL"); 
        sellBtn.setBackground(new Color(255, 69, 96)); // Red
        sellBtn.setForeground(Color.WHITE);
        sellBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));

        tradePanel.add(tradeTitle);
        tradePanel.add(Box.createVerticalStrut(20));
        tradePanel.add(makeLabel("Symbol")); tradePanel.add(symField);
        tradePanel.add(Box.createVerticalStrut(15));
        tradePanel.add(makeLabel("Quantity")); tradePanel.add(qtyField);
        tradePanel.add(Box.createVerticalStrut(25));
        
        JPanel tradeButtons = new JPanel(new GridLayout(1,2, 10, 0)); 
        tradeButtons.setBackground(new Color(19, 23, 34));
        tradeButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        tradeButtons.add(buyBtn); tradeButtons.add(sellBtn);
        tradePanel.add(tradeButtons);

        centerCards.add(portPanel, "portfolio");
        centerCards.add(chartPanel, "charts");
        centerCards.add(tradePanel, "trade");
        add(centerCards, BorderLayout.CENTER);

        // Right: market + transactions
        JPanel right = new JPanel(new BorderLayout(8,8));
        right.setBackground(new Color(19, 23, 34));
        right.setBorder(new EmptyBorder(0,5,0,0));
        right.setPreferredSize(new Dimension(380,0));

        marketModel = new DefaultTableModel(new String[]{"Name","Symbol","Price (₹)","Δ (%)"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        marketTable = new JTable(marketModel);
        styleTable(marketTable);
        marketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        right.add(new JScrollPane(marketTable), BorderLayout.CENTER);

        txModel = new DefaultTableModel(new String[]{"Type","Symbol","Qty","Price"},0) {
            public boolean isCellEditable(int r,int c){return false;}
        };
        txTable = new JTable(txModel);
        styleTable(txTable);
        txTable.setRowHeight(24);
        JScrollPane txScroll = new JScrollPane(txTable);
        txScroll.setPreferredSize(new Dimension(380, 200));
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

        // Default selection
        chartCanvas.setStock(engine.getStock("TITAN"));
        symField.setText("TITAN");

        // start engine updates and UI refresh
        engine.start(() -> SwingUtilities.invokeLater(this::refreshAll));
        refreshAll();
    }

    private JLabel makeLabel(String txt) {
        JLabel l = new JLabel(txt);
        l.setForeground(Color.GRAY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JButton makeNavButton(String text) {
        JButton b = new JButton(text);
        b.setMaximumSize(new Dimension(220,44));
        b.setBackground(new Color(28, 32, 45));
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(45, 50, 65)), 
            BorderFactory.createEmptyBorder(10, 20, 10, 20)
        ));
        return b;
    }

    private void styleTable(JTable t) {
        t.setBackground(new Color(19, 23, 34));
        t.setForeground(new Color(209, 212, 220));
        t.setFont(new Font("Consolas", Font.PLAIN, 13));
        t.getTableHeader().setBackground(new Color(28, 32, 45));
        t.getTableHeader().setForeground(Color.WHITE);
        t.getTableHeader().setBorder(null);
        t.setGridColor(new Color(43, 43, 67));
        t.setShowVerticalLines(false);
    }

    public void refreshAll() {
        // update balance label
        User u = controller.getCurrentUser();
        if (u != null) balanceLabel.setText("Balance: ₹" + df.format(u.getBalance()));
        else balanceLabel.setText("Balance: ₹0.00");

        // market table
        int selectedRow = marketTable.getSelectedRow();
        marketModel.setRowCount(0);
        for (Stock s : engine.getStocks()) {
            double chg = s.getDailyChangePercent();
            String chgStr = (chg >= 0 ? "+" : "") + String.format("%.2f%%", chg);
            marketModel.addRow(new Object[]{s.getName(), s.getSymbol(), s.getPrice(), chgStr});
        }
        if (selectedRow >= 0 && selectedRow < marketModel.getRowCount()) 
            marketTable.setRowSelectionInterval(selectedRow, selectedRow);

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
            txModel.addRow(new Object[]{t.getType(), t.getSymbol(), t.getQuantity(), String.format("%.2f", t.getPrice())});
        }

        chartCanvas.repaint();
    }

    /* * NEW: High-Quality TradingView Style Chart Canvas 
     * Implements specific colors, gradients, and grid lines.
     */
    private static class ChartCanvas extends JPanel {
        private Stock stock;
        
        // TradingView Colors
        private final Color BG_COLOR = new Color(19, 23, 34);
        private final Color GRID_COLOR = new Color(43, 43, 67);
        private final Color LINE_COLOR = new Color(0, 227, 150); // Neon Green/Teal
        private final Color FILL_TOP = new Color(0, 227, 150, 60);
        private final Color FILL_BOTTOM = new Color(0, 227, 150, 0);
        private final Color TEXT_COLOR = new Color(120, 123, 134);

        ChartCanvas() {
            setBackground(BG_COLOR);
            setPreferredSize(new Dimension(600,420));
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

            int w = getWidth();
            int hgt = getHeight();
            int paddingRight = 60; // Space for price labels
            int paddingBottom = 20;
            int chartW = w - paddingRight;
            int chartH = hgt - paddingBottom;

            // 1. Calculate Scale
            double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
            for (Stock.PricePoint p : h) { 
                min = Math.min(min, p.price); 
                max = Math.max(max, p.price); 
            }
            if (min == max) { min -= 1; max += 1; }
            double range = max - min;

            // 2. Draw Grid & Labels
            g.setColor(GRID_COLOR);
            g.setStroke(new BasicStroke(1));
            
            // Horizontal grid lines
            int gridRows = 5;
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (int i = 0; i <= gridRows; i++) {
                int y = (int) (chartH - (i * (chartH / (double)gridRows)));
                g.drawLine(0, y, chartW, y);
                
                // Price Label on Right Axis
                double priceLabel = min + (i * (range / gridRows));
                g.setColor(TEXT_COLOR);
                g.drawString(String.format("%.2f", priceLabel), chartW + 5, y + 4);
                g.setColor(GRID_COLOR);
            }
            
            // Vertical grid lines
            int gridCols = 6;
            for (int i = 0; i <= gridCols; i++) {
                int x = (int) (i * (chartW / (double)gridCols));
                g.drawLine(x, 0, x, chartH);
            }

            // 3. Create Line Path & Fill Path
            int n = h.size();
            Path2D.Double linePath = new Path2D.Double();
            Path2D.Double fillPath = new Path2D.Double();

            double xStep = (double) chartW / (n - 1);
            
            // Start points
            double firstY = chartH - ((h.get(0).price - min) / range * chartH);
            linePath.moveTo(0, firstY);
            fillPath.moveTo(0, chartH); // Bottom left
            fillPath.lineTo(0, firstY);

            for (int i = 1; i < n; i++) {
                double x = i * xStep;
                double y = chartH - ((h.get(i).price - min) / range * chartH);
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }

            fillPath.lineTo((n-1)*xStep, chartH); // Bottom right
            fillPath.closePath();

            // 4. Draw Gradient Fill
            GradientPaint gp = new GradientPaint(0, 0, FILL_TOP, 0, chartH, FILL_BOTTOM);
            g.setPaint(gp);
            g.fill(fillPath);

            // 5. Draw Line
            g.setColor(LINE_COLOR);
            g.setStroke(new BasicStroke(2.0f));
            g.draw(linePath);

            // 6. Draw Header Info
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 16));
            g.drawString(stock.getSymbol(), 20, 30);
            
            g.setColor(LINE_COLOR);
            g.setFont(new Font("Consolas", Font.BOLD, 16));
            g.drawString("₹" + String.format("%.2f", stock.getPrice()), 20, 55);
        }
    }
}