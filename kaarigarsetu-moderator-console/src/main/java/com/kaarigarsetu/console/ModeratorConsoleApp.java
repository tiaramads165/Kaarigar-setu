package com.kaarigarsetu.console;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Moderator console: the human review step for fraud-flagged attestations ("Human Review Recommended").
 * Sign in with the shared service account plus an OTP, review each flagged attestation with the evidence the model
 * saw, then approve or reject it. Every decision is written to the audit trail by the backend.
 *
 * <p>Backend address: first program argument, or the KS_BASE_URL environment variable, default http://localhost:8090.
 */
public class ModeratorConsoleApp extends Application {
    private ModeratorApi api;
    private Stage stage;
    private String baseUrl = "";
    private final Label status = new Label();
    private boolean queueShown;
    private final ObservableList<FlaggedRow> rows = FXCollections.observableArrayList();
    private final ObservableList<SecurityOverview.Count> counts = FXCollections.observableArrayList();
    private final ObservableList<SecurityOverview.Event> events = FXCollections.observableArrayList();
    private final Label securityStatus = new Label("Security status: not loaded yet");
    private final VBox alertBox = new VBox(6);
    private final Label integrityLine = new Label();
    private final ObservableList<EmployerRow> employerRows = FXCollections.observableArrayList();
    private final javafx.scene.control.CheckBox onlyUnvetted = new javafx.scene.control.CheckBox("Only employers nobody has vetted yet");
    private final ObservableList<DisputeRow> disputeRows = FXCollections.observableArrayList();
    private final ObservableList<PendingRow> pendingRows = FXCollections.observableArrayList();
    private TableView<FlaggedRow> queueTable;
    private TabPane tabs;

    @Override
    public void start(Stage primaryStage) {
        List<String> args = getParameters().getRaw();
        String baseUrl = !args.isEmpty() ? args.get(0) : System.getenv().getOrDefault("KS_BASE_URL", "http://localhost:8090");
        this.baseUrl = baseUrl;
        this.api = new HttpModeratorApi(baseUrl);
        this.stage = primaryStage;
        stage.setTitle("Kaarigar Setu - Moderator Console");
        stage.setScene(new Scene(loginPane(baseUrl), 460, 360));
        stage.show();
        String snapshotDir = System.getProperty("ks.console.snapshot");
        if (snapshotDir != null) renderDocumentationScreens(Path.of(snapshotDir));
    }

    /**
     * Documentation aid (-Dks.console.snapshot=DIR): renders the sign-in screen and the review queue, filled with
     * clearly fictional DEMO rows, to PNG files and exits. It touches no network and uses no credentials.
     */
    private void renderDocumentationScreens(Path directory) {
        try {
            Files.createDirectories(directory);
            savePng(stage.getScene(), directory.resolve("console-login.png"));
            rows.setAll(List.of(
                    new FlaggedRow(12, 31, 0.78, "{\"ip_frequency\":9.0,\"device_frequency\":7.0,\"minutes_since_previous\":2.1,\"reciprocity_ratio\":0.0}", 14, 6, "DEMO: house wiring", "2026-10-07T10:42:11"),
                    new FlaggedRow(13, 32, 0.66, "{\"ip_frequency\":1.0,\"device_frequency\":1.0,\"minutes_since_previous\":900.0,\"reciprocity_ratio\":0.92}", 9, 11, "DEMO: pipe fitting", "2026-10-07T11:05:40"),
                    new FlaggedRow(14, 35, 0.52, "{\"note\":\"ml_unavailable\"}", 21, 6, "DEMO: switchboard repair", "2026-10-07T11:30:02")));
            showQueueView(false);
            status.setText("DEMO DATA - 3 attestation(s) awaiting review.");
            queueTable.getSelectionModel().select(0);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            savePng(stage.getScene(), directory.resolve("console-queue.png"));
            tabs.getSelectionModel().select(1);
            employerRows.setAll(List.of(new EmployerRow(6, "DEMO: Sharma Contractors", false, "", 7, "2026-08-02T10:15:00"),
                    new EmployerRow(11, "DEMO: Verma Builders", false, "", 3, "2026-09-11T16:40:00"),
                    new EmployerRow(4, "DEMO: Singh Electricals", true, "alice (approved by bob)", 12, "2026-06-20T09:05:00")));
            onlyUnvetted.setSelected(false);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            savePng(stage.getScene(), directory.resolve("console-employers.png"));
            tabs.getSelectionModel().select(2);
            disputeRows.setAll(List.of(new DisputeRow(41, 14, 6, "DEMO: Sharma Contractors", "DEMO: house wiring", "2026-10-02", "quality 1, punctuality 1, conduct 1", "no",
                            "I never worked for this contractor; the job was done for a different person on that date.", "2026-10-08T09:12:00"),
                    new DisputeRow(44, 21, 11, "DEMO: Verma Builders", "DEMO: pipe fitting", "2026-09-28", "quality 2, punctuality 3, conduct 4", "yes",
                            "The rating is lower than the work I did, I want it checked.", "2026-10-09T14:40:00")));
            ((TableView<?>) ((VBox) tabs.getTabs().get(2).getContent()).getChildren().get(2)).getSelectionModel().select(0);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            savePng(stage.getScene(), directory.resolve("console-disputes.png"));
            tabs.getSelectionModel().select(3);
            pendingRows.setAll(List.of(new PendingRow(7, "VET_EMPLOYER", "employer 6 (DEMO: Sharma Contractors)", null, "alice", "2026-10-22T09:30:00", "2026-10-25T09:30:00", true),
                    new PendingRow(8, "DISPUTE_DECISION", "confirmation 41 from DEMO: Verma Builders (DEMO: pipe fitting), worker 14", "REMOVED", "bob", "2026-10-22T11:05:00", "2026-10-25T11:05:00", true),
                    new PendingRow(9, "VET_EMPLOYER", "employer 11 (DEMO: Verma Builders)", null, "moderator", "2026-10-22T12:00:00", "2026-10-25T12:00:00", false)));
            ((TableView<?>) ((VBox) tabs.getTabs().get(3).getContent()).getChildren().get(2)).getSelectionModel().select(0);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            savePng(stage.getScene(), directory.resolve("console-approvals.png"));
            tabs.getSelectionModel().select(4);
            showSecurity(new SecurityOverview("WARN", List.of(
                    new SecurityOverview.Alert("WARN", "Repeated failed sign-ins", "DEMO: 14 in the last hour: someone may be guessing codes or passwords."),
                    new SecurityOverview.Alert("WARN", "Requests rejected by CSRF protection", "DEMO: 4 in the last hour: a cross-site attack, or a broken client, is hitting the API.")),
                    List.of(new SecurityOverview.Count("LOGIN_FAILED", 14, 31), new SecurityOverview.Count("CSRF_REJECTED", 4, 4), new SecurityOverview.Count("RATE_LIMITED", 3, 22),
                            new SecurityOverview.Count("ACCESS_DENIED", 0, 1), new SecurityOverview.Count("CREDENTIAL_REVOKED", 1, 2)),
                    List.of(new SecurityOverview.Event("2026-10-16T01:31:07", "CSRF_REJECTED", "anonymous", "method=POST path=/api/auth/logout client=3fa91c0b77d2"),
                            new SecurityOverview.Event("2026-10-16T01:29:44", "LOGIN_FAILED", "anonymous", "method=otp role=WORKER reason=bad code")),
                    "Audit file: intact, 812 lines, 33 database anchors checked; database audit chain intact; newest anchor 2 min ago."));
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            savePng(stage.getScene(), directory.resolve("console-security.png"));
        } catch (IOException failure) {
            System.err.println("Could not write screenshots: " + failure.getMessage());
        } finally {
            Platform.exit();
        }
    }

    private static void savePng(Scene scene, Path file) throws IOException {
        javafx.scene.image.WritableImage image = scene.snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) out.setRGB(x, y, reader.getArgb(x, y));
        }
        javax.imageio.ImageIO.write(out, "png", file.toFile());
    }

    // ---------------------------------------------------------------- sign-in (password, then OTP)

    private VBox loginPane(String baseUrl) {
        return loginPane(baseUrl, "Server: " + baseUrl);
    }

    private VBox loginPane(String baseUrl, String notice) {
        Label title = new Label("Moderator sign-in");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        TextField user = new TextField("moderator");
        user.setPromptText("Username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        Button signIn = new Button("Sign in");
        TextField code = new TextField();
        code.setPromptText("6-digit code");
        Button verify = new Button("Verify code");
        code.setDisable(true);
        verify.setDisable(true);
        Label message = new Label(notice);
        message.setWrapText(true);

        signIn.setDefaultButton(true);
        signIn.setOnAction(e -> background(() -> {
            api.login(user.getText().trim(), password.getText().toCharArray());
            return null;
        }, ok -> {
            password.clear();                       // never keep the password around
            message.setText("Password accepted. Enter the 6-digit code sent to the moderator's channel. (Local development: it is printed in the server log as purpose=moderator.) The code expires after 5 minutes.");
            code.setDisable(false);
            verify.setDisable(false);
            code.requestFocus();
        }, error -> message.setText(error)));

        verify.setOnAction(e -> background(() -> {
            api.verifyOtp(code.getText().trim());
            return null;
        }, ok -> {
            showQueueView(true);
            background(api::me, me -> stage.setTitle("Kaarigar Setu - Moderator Console - signed in as " + me.username() + " (" + me.displayName() + ")"), error -> { });
        }, error -> message.setText(error)));

        VBox box = new VBox(10, title, new Label("Username"), user, new Label("Password"), password, signIn,
                new Label("One-time code"), code, verify, message);
        box.setPadding(new Insets(20));
        return box;
    }

    // ---------------------------------------------------------------- the review queue

    private void showQueueView(boolean load) {
        TableView<FlaggedRow> table = new TableView<>(rows);
        table.setPlaceholder(new Label("No flagged attestations are waiting for review."));
        table.getColumns().add(column("Flag", 60, r -> new SimpleLongProperty(r.flagId()).asObject().asString()));
        table.getColumns().add(column("Attestation", 90, r -> new SimpleLongProperty(r.attestationId()).asObject().asString()));
        table.getColumns().add(column("Worker", 70, r -> new SimpleLongProperty(r.workerId()).asObject().asString()));
        table.getColumns().add(column("Employer", 80, r -> new SimpleLongProperty(r.employerId()).asObject().asString()));
        table.getColumns().add(column("Work described", 160, r -> new SimpleStringProperty(r.jobRole())));
        TableColumn<FlaggedRow, String> score = column("Anomaly", 80, r -> new SimpleDoubleProperty(r.anomalyScore()).asObject().asString("%.2f"));
        score.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(empty || item == null ? "" : (Double.parseDouble(item) >= 0.7 ? "-fx-text-fill: #b71c1c; -fx-font-weight: bold;" : "-fx-text-fill: #a15c00;"));
            }
        });
        table.getColumns().add(score);
        table.getColumns().add(column("Evidence the model saw", 330, r -> new SimpleStringProperty(r.features())));
        table.getColumns().add(column("Flagged at", 160, r -> new SimpleStringProperty(r.createdAt())));

        Button approve = new Button("Approve (genuine)");
        Button reject = new Button("Reject (fraud)");
        Button refresh = new Button("Refresh");
        Button audit = new Button("Verify audit log");
        Button signOut = new Button("Sign out");
        approve.setStyle("-fx-base: #2e7d32;");
        reject.setStyle("-fx-base: #c62828;");
        approve.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        reject.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        approve.setOnAction(e -> decide(table.getSelectionModel().getSelectedItem(), "VALID"));
        reject.setOnAction(e -> decide(table.getSelectionModel().getSelectedItem(), "FRAUD"));
        refresh.setOnAction(e -> loadQueue());
        audit.setOnAction(e -> background(api::auditCheck, status::setText, status::setText));
        signOut.setOnAction(e -> {
            api.logout();
            rows.clear();
            stage.setScene(new Scene(loginPane(baseUrl, "Signed out. Server: " + baseUrl), 460, 360));
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, approve, reject, spacer, audit, refresh, signOut);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        status.setPadding(new Insets(6, 10, 6, 10));

        TextArea detail = new TextArea("Select a row to see the full evidence.");
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(6);
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                detail.setText(selected == null ? "Select a row to see the full evidence." : selected.explain()));

        queueTable = table;
        BorderPane fraudPane = new BorderPane(table);
        fraudPane.setBottom(detail);
        Tab fraudTab = new Tab("Fraud review", fraudPane);
        Tab securityTab = new Tab("Security", securityPane());
        fraudTab.setClosable(false);
        securityTab.setClosable(false);
        securityTab.setOnSelectionChanged(e -> {
            if (securityTab.isSelected()) loadSecurity();      // opening the tab always shows fresh numbers
        });
        Tab employersTab = new Tab("Employers", employersPane());
        employersTab.setClosable(false);
        employersTab.setOnSelectionChanged(e -> {
            if (employersTab.isSelected()) loadEmployers();
        });
        Tab disputesTab = new Tab("Disputes", disputesPane());
        disputesTab.setClosable(false);
        disputesTab.setOnSelectionChanged(e -> {
            if (disputesTab.isSelected()) loadDisputes();
        });
        Tab approvalsTab = new Tab("Approvals", approvalsPane());
        approvalsTab.setClosable(false);
        approvalsTab.setOnSelectionChanged(e -> {
            if (approvalsTab.isSelected()) loadPending();
        });
        tabs = new TabPane(fraudTab, employersTab, disputesTab, approvalsTab, securityTab);
        BorderPane root = new BorderPane(tabs);
        root.setTop(bar);
        root.setBottom(status);
        stage.setScene(new Scene(root, 1100, 720));
        queueShown = true;
        if (load) loadQueue();
    }

    private void loadQueue() {
        status.setText("Loading the review queue...");
        background(api::flagged, list -> {
            rows.setAll(list);
            status.setText(list.size() + " attestation(s) awaiting review.");
        }, status::setText);
    }

    // ---------------------------------------------------------------- the employers tab (vetting)

    private VBox employersPane() {
        Label explain = new Label("Vetting is the trust anchor of the Excellent band: a worker cannot reach it unless at least one confirming employer has been vetted. "
                + "Vet an employer only after checking who they are OUTSIDE this application (business registration, a call back, a contractor association). "
                + "Vetting is proposed by one moderator and approved by a different one (Approvals tab); both names are recorded in the audit trail.");
        explain.setWrapText(true);
        TableView<EmployerRow> table = new TableView<>(employerRows);
        table.setPlaceholder(new Label("No employers to show."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(plain("Id", 70, r -> new SimpleStringProperty(Long.toString(r.employerId()))));
        table.getColumns().add(plain("Name", 300, r -> new SimpleStringProperty(r.name())));
        table.getColumns().add(plain("Approved confirmations", 160, r -> new SimpleStringProperty(Integer.toString(r.approvedConfirmations()))));
        table.getColumns().add(plain("Registered", 170, r -> new SimpleStringProperty(r.registeredAt())));
        table.getColumns().add(plain("Status", 220, r -> new SimpleStringProperty(r.vetted() ? "Vetted" + (r.vettedBy().isEmpty() ? "" : " by " + r.vettedBy()) : "Not vetted")));
        VBox.setVgrow(table, Priority.ALWAYS);
        Button vet = new Button("Propose vetting of selected employer");
        vet.setStyle("-fx-base: #2e7d32;");
        vet.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button reload = new Button("Refresh");
        onlyUnvetted.setSelected(true);
        onlyUnvetted.setOnAction(e -> loadEmployers());
        reload.setOnAction(e -> loadEmployers());
        vet.setOnAction(e -> {
            EmployerRow row = table.getSelectionModel().getSelectedItem();
            if (row == null || row.vetted()) {
                status.setText(row == null ? "Select an employer first." : "Employer " + row.employerId() + " is already vetted.");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Propose vetting \"" + row.name() + "\" (employer " + row.employerId() + ")?\nOnly do this after checking their identity or "
                    + "business outside this application. Vetting takes effect when a DIFFERENT moderator approves it (Approvals tab). It is recorded in the audit trail under your name.", ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Confirm proposal");
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
            background(() -> api.vetEmployer(row.employerId()), result -> {
                status.setText(result.pending() ? "Vetting of employer " + row.employerId() + " proposed (#" + result.actionId() + "): a different moderator must approve it in the Approvals tab."
                        : "Employer " + row.employerId() + " vetted; trust scores are being refreshed.");
                loadEmployers();
            }, status::setText);
        });
        HBox bar = new HBox(10, vet, onlyUnvetted, new Region(), reload);
        HBox.setHgrow(bar.getChildren().get(2), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, explain, bar, table);
        box.setPadding(new Insets(10));
        return box;
    }

    private void loadEmployers() {
        background(() -> api.employers(onlyUnvetted.isSelected()), list -> {
            employerRows.setAll(list);
            status.setText(list.size() + " employer(s) listed.");
        }, status::setText);
    }

    // ---------------------------------------------------------------- the disputes tab

    private VBox disputesPane() {
        Label explain = new Label("A worker can dispute a confirmation an employer wrote about them. While it is open it does not count toward the trust score or the credential. "
                + "Read the worker's reason and the employer's side, check outside this application if you can, then propose a ruling: KEEP (the confirmation counts again) or REMOVE "
                + "(it never counts). A different moderator approves it (Approvals tab); both names are recorded in the audit trail.");
        explain.setWrapText(true);
        TableView<DisputeRow> table = new TableView<>(disputeRows);
        table.setPlaceholder(new Label("No open disputes."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(plain("Confirmation", 100, r -> new SimpleStringProperty(Long.toString(r.attestationId()))));
        table.getColumns().add(plain("Worker", 70, r -> new SimpleStringProperty(Long.toString(r.workerId()))));
        table.getColumns().add(plain("Employer", 240, r -> new SimpleStringProperty(r.employerName())));
        table.getColumns().add(plain("Job", 160, r -> new SimpleStringProperty(r.jobRole())));
        table.getColumns().add(plain("Ratings", 260, r -> new SimpleStringProperty(r.ratings())));
        table.getColumns().add(plain("Disputed", 170, r -> new SimpleStringProperty(r.disputedAt())));
        VBox.setVgrow(table, Priority.ALWAYS);
        Label detail = new Label("Select a dispute to read the worker's reason.");
        detail.setWrapText(true);
        detail.setMinHeight(110);
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                detail.setText(selected == null ? "Select a dispute to read the worker's reason." : selected.explain()));
        Button keep = new Button("Propose: keep (upheld)");
        keep.setStyle("-fx-base: #2e7d32;");
        Button remove = new Button("Propose: remove");
        remove.setStyle("-fx-base: #c62828;");
        keep.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button reload = new Button("Refresh");
        reload.setOnAction(e -> loadDisputes());
        keep.setOnAction(e -> rule(table.getSelectionModel().getSelectedItem(), "UPHELD"));
        remove.setOnAction(e -> rule(table.getSelectionModel().getSelectedItem(), "REMOVED"));
        HBox bar = new HBox(10, keep, remove, new Region(), reload);
        HBox.setHgrow(bar.getChildren().get(2), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, explain, bar, table, detail);
        box.setPadding(new Insets(10));
        return box;
    }

    private void rule(DisputeRow row, String decision) {
        if (row == null) return;
        boolean keepIt = "UPHELD".equals(decision);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Propose to " + (keepIt ? "KEEP the confirmation from " : "REMOVE the confirmation from ") + row.employerName() + " about worker "
                + row.workerId() + "?\n" + (keepIt ? "It will count toward the worker's trust score again." : "It will never count toward the worker's trust score.")
                + "\nA ruling takes effect, and is final, when a DIFFERENT moderator approves it (Approvals tab). It is recorded in the audit trail under your name.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(keepIt ? "Confirm proposal: keep" : "Confirm proposal: remove");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
        background(() -> api.decideDispute(row.attestationId(), decision), result -> {
            status.setText(result.pending() ? "Ruling on confirmation " + row.attestationId() + " proposed (#" + result.actionId() + "): a different moderator must approve it in the Approvals tab."
                    : "Confirmation " + row.attestationId() + (keepIt ? " kept" : " removed") + "; the worker's trust score is being refreshed.");
            loadDisputes();
        }, status::setText);
    }

    private void loadDisputes() {
        background(api::disputes, list -> {
            disputeRows.setAll(list);
            status.setText(list.size() + " open dispute(s).");
        }, status::setText);
    }

    // ---------------------------------------------------------------- the approvals tab (the second person)

    private VBox approvalsPane() {
        Label explain = new Label("Vetting an employer and ruling on a dispute move trust, so they take effect only when a DIFFERENT moderator approves what the first one proposed. "
                + "Approve only what you have checked yourself; you cannot approve your own proposal, and a proposal that nobody decides expires. "
                + "Your approval is recorded under your name next to the proposer's.");
        explain.setWrapText(true);
        TableView<PendingRow> table = new TableView<>(pendingRows);
        table.setPlaceholder(new Label("Nothing is waiting for approval."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(plain("#", 60, r -> new SimpleStringProperty(Long.toString(r.actionId()))));
        table.getColumns().add(plain("Proposal", 230, r -> new SimpleStringProperty(r.what())));
        table.getColumns().add(plain("About", 420, r -> new SimpleStringProperty(r.target())));
        table.getColumns().add(plain("Proposed by", 110, r -> new SimpleStringProperty(r.proposedBy())));
        table.getColumns().add(plain("Proposed", 170, r -> new SimpleStringProperty(r.proposedAt())));
        VBox.setVgrow(table, Priority.ALWAYS);
        Label detail = new Label("Select a proposal to read it.");
        detail.setWrapText(true);
        detail.setMinHeight(90);
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                detail.setText(selected == null ? "Select a proposal to read it." : selected.explain()));
        Button approve = new Button("Approve");
        approve.setStyle("-fx-base: #2e7d32;");
        Button reject = new Button("Reject / withdraw");
        reject.setStyle("-fx-base: #c62828;");
        approve.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            PendingRow selected = table.getSelectionModel().getSelectedItem();
            return selected == null || !selected.canDecide();                 // your own proposals cannot be approved by you
        }, table.getSelectionModel().selectedItemProperty()));
        reject.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button reload = new Button("Refresh");
        reload.setOnAction(e -> loadPending());
        approve.setOnAction(e -> {
            PendingRow row = table.getSelectionModel().getSelectedItem();
            if (row == null || !row.canDecide()) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Approve: " + row.what() + "\n" + row.target() + "\nproposed by " + row.proposedBy()
                    + ".\nIt takes effect now and is recorded under both your names.", ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Confirm approval");
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
            background(() -> {
                api.approve(row.actionId());
                return null;
            }, ok -> {
                status.setText("Approved #" + row.actionId() + ": " + row.what() + " has taken effect; trust scores are being refreshed.");
                loadPending();
            }, status::setText);
        });
        reject.setOnAction(e -> {
            PendingRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) return;
            background(() -> {
                api.reject(row.actionId());
                return null;
            }, ok -> {
                status.setText((row.canDecide() ? "Rejected" : "Withdrew") + " proposal #" + row.actionId() + ".");
                loadPending();
            }, status::setText);
        });
        HBox bar = new HBox(10, approve, reject, new Region(), reload);
        HBox.setHgrow(bar.getChildren().get(2), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, explain, bar, table, detail);
        box.setPadding(new Insets(10));
        return box;
    }

    private void loadPending() {
        background(api::pending, list -> {
            pendingRows.setAll(list);
            long mine = list.stream().filter(r -> !r.canDecide()).count();
            status.setText(list.size() + " proposal(s) waiting; " + (list.size() - mine) + " for you to decide, " + mine + " of your own waiting for someone else.");
        }, status::setText);
    }

    // ---------------------------------------------------------------- the security tab

    private VBox securityPane() {
        securityStatus.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Button reload = new Button("Refresh security view");
        reload.setOnAction(e -> loadSecurity());
        TableView<SecurityOverview.Count> countTable = new TableView<>(counts);
        countTable.setPlaceholder(new Label("No data yet."));
        countTable.setMinHeight(262);
        countTable.setPrefHeight(262);
        countTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        countTable.getColumns().add(plain("Event", 330, c -> new SimpleStringProperty(SecurityOverview.describe(c.type()))));
        countTable.getColumns().add(plain("Last hour", 90, c -> new SimpleStringProperty(Long.toString(c.lastHour()))));
        countTable.getColumns().add(plain("Last 24 h", 90, c -> new SimpleStringProperty(Long.toString(c.last24h()))));
        TableView<SecurityOverview.Event> eventTable = new TableView<>(events);
        eventTable.setPlaceholder(new Label("No security events recorded yet."));
        eventTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        eventTable.getColumns().add(plain("Time (server)", 170, e -> new SimpleStringProperty(e.at())));
        eventTable.getColumns().add(plain("Event", 230, e -> new SimpleStringProperty(SecurityOverview.describe(e.type()))));
        eventTable.getColumns().add(plain("Who", 110, e -> new SimpleStringProperty(e.actor())));
        eventTable.getColumns().add(plain("Details", 520, e -> new SimpleStringProperty(e.details())));
        VBox.setVgrow(eventTable, Priority.ALWAYS);
        integrityLine.setWrapText(true);
        Label note = new Label("Rate-limit, CSRF and role-violation events are counted once per client per minute, so the numbers mean distinct clients, not requests.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #555;");
        HBox head = new HBox(12, securityStatus, new Region(), reload);
        HBox.setHgrow(head.getChildren().get(1), Priority.ALWAYS);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, head, alertBox, new Label("Security events"), countTable, new Label("Most recent"), eventTable, integrityLine, note);
        box.setPadding(new Insets(10));
        return box;
    }

    private void loadSecurity() {
        securityStatus.setText("Security status: loading...");
        background(api::securityOverview, this::showSecurity, message -> securityStatus.setText("Security status: " + message));
    }

    private void showSecurity(SecurityOverview overview) {
        String color = switch (overview.status()) {
            case "OK" -> "#2e7d32";
            case "CRITICAL" -> "#b71c1c";
            default -> "#a15c00";
        };
        securityStatus.setText("Security status: " + overview.status());
        securityStatus.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        alertBox.getChildren().clear();
        if (overview.alerts().isEmpty()) {
            Label none = new Label("No alerts: every counter is below its threshold and the audit trail verifies.");
            none.setStyle("-fx-text-fill: #2e7d32;");
            alertBox.getChildren().add(none);
        }
        for (SecurityOverview.Alert alert : overview.alerts()) {
            Label label = new Label(alert.level() + ": " + alert.title() + " - " + alert.detail());
            label.setWrapText(true);
            label.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (alert.level().equals("WARN") ? "#a15c00" : "#b71c1c") + ";");
            alertBox.getChildren().add(label);
        }
        counts.setAll(overview.counts());
        events.setAll(overview.recent());
        integrityLine.setText(overview.integrity());
    }

    private static <T> TableColumn<T, String> plain(String title, double width, java.util.function.Function<T, javafx.beans.value.ObservableValue<String>> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        return column;
    }

    private void decide(FlaggedRow row, String decision) {
        if (row == null) return;
        boolean approve = decision.equals("VALID");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                (approve ? "Approve" : "REJECT as fraud") + " attestation " + row.attestationId() + " (anomaly " + String.format("%.2f", row.anomalyScore())
                        + ")?\nThis is recorded in the audit trail and cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Confirm decision");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
        background(() -> api.decide(row.flagId(), decision), approved -> {
            status.setText("Attestation " + row.attestationId() + (approved ? " approved." : " rejected."));
            loadQueue();
        }, error -> {
            status.setText(error);
            loadQueue();   // e.g. someone else already decided: show the current queue
        });
    }

    // ---------------------------------------------------------------- helpers

    private static <S> TableColumn<FlaggedRow, S> column(String title, double width, java.util.function.Function<FlaggedRow, javafx.beans.value.ObservableValue<S>> value) {
        TableColumn<FlaggedRow, S> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        return column;
    }

    /** Runs a backend call off the UI thread; results come back on it. */
    private <T> void background(Supplier<T> work, Consumer<T> onSuccess, Consumer<String> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable failure = task.getException();
            Platform.runLater(() -> {
                if (failure instanceof ConsoleException console && "unauthenticated".equals(console.code()) && queueShown) {
                    // the session ended while the queue was open (timeout, server restart): go back to sign-in instead of staying stuck
                    rows.clear();
                    queueShown = false;
                    stage.setScene(new Scene(loginPane(baseUrl, "Your session has ended. Please sign in again."), 460, 360));
                    return;
                }
                onError.accept(failure instanceof ConsoleException ? failure.getMessage() : "Unexpected error.");
            });
        });
        Thread thread = new Thread(task, "console-io");
        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
