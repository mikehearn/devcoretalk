package timestamper;

import com.subgraph.orchid.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.event.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.*;
import org.bitcoinj.core.*;
import org.bitcoinj.script.*;
import org.bitcoinj.store.*;
import org.bitcoinj.utils.*;
import org.fxmisc.easybind.*;
import timestamper.controls.*;
import timestamper.utils.*;
import timestamper.utils.easing.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static timestamper.Main.*;
import static timestamper.utils.GuiUtils.*;

/**
 * Gets created auto-magically by FXMLLoader via reflection. The widget fields are set to the GUI controls they're named
 * after. This class handles all the updates and event handling for the main UI.
 */
public class MainController {
    public HBox controlsBox;
    public Label balance;
    public Button sendMoneyOutBtn;
    public ClickableBitcoinAddress addressControl;
    public ListView<Proof> pendingProofsList;

    private static class Proof implements Serializable {
        byte[] tx, partialMerkleTree;
        Sha256Hash blockHash;

        transient SimpleIntegerProperty depth = new SimpleIntegerProperty();
        transient String filename;

        public void saveTo(String filename) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(filename)))) {
                oos.writeObject(this);
            }
        }

        public static Proof readFrom(String filename) throws IOException {
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(Paths.get(filename)))) {
                return (Proof) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private BitcoinUIModel model = new BitcoinUIModel();
    private NotificationBarPane.Item syncItem;

    // Called by FXMLLoader.
    public void initialize() {
        addressControl.setOpacity(0.0);

        pendingProofsList.setCellFactory(new Callback<ListView<Proof>, ListCell<Proof>>() {
            @Override
            public ListCell<Proof> call(ListView<Proof> param) {
                return new ListCell<Proof>() {
                    @Override
                    protected void updateItem(Proof item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setText("");
                            setGraphic(null);
                        } else {
                            setText("Proof for " + item.filename);
                            ProgressBar bar = new ProgressBar();
                            bar.progressProperty().bind(item.depth.divide(3.0));
                            setGraphic(bar);
                        }
                    }
                };
            }
        });
    }

    public void onBitcoinSetup() {
        model.setWallet(bitcoin.wallet());
        addressControl.addressProperty().bind(model.addressProperty());
        balance.textProperty().bind(EasyBind.map(model.balanceProperty(), coin -> MonetaryFormat.BTC.noCode().format(coin).toString()));
        // Don't let the user click send money when the wallet is empty.
        sendMoneyOutBtn.disableProperty().bind(model.balanceProperty().isEqualTo(Coin.ZERO));

        TorClient torClient = Main.bitcoin.peerGroup().getTorClient();
        if (torClient != null) {
            SimpleDoubleProperty torProgress = new SimpleDoubleProperty(-1);
            String torMsg = "Initialising Tor";
            syncItem = Main.instance.notificationBar.pushItem(torMsg, torProgress);
            torClient.addInitializationListener(new TorInitializationListener() {
                @Override
                public void initializationProgress(String message, int percent) {
                    Platform.runLater(() -> {
                        syncItem.label.set(torMsg + ": " + message);
                        torProgress.set(percent / 100.0);
                    });
                }

                @Override
                public void initializationCompleted() {
                    Platform.runLater(() -> {
                        syncItem.cancel();
                        showBitcoinSyncMessage();
                    });
                }
            });
        } else {
            showBitcoinSyncMessage();
        }
        model.syncProgressProperty().addListener(x -> {
            if (model.syncProgressProperty().get() >= 1.0) {
                readyToGoAnimation();
                if (syncItem != null) {
                    syncItem.cancel();
                    syncItem = null;
                }
            } else if (syncItem == null) {
                showBitcoinSyncMessage();
            }
        });
    }

    private void showBitcoinSyncMessage() {
        syncItem = Main.instance.notificationBar.pushItem("Synchronising with the Bitcoin network", model.syncProgressProperty());
    }

    public void sendMoneyOut(ActionEvent event) {
        // Hide this UI and show the send money UI. This UI won't be clickable until the user dismisses send_money.
        Main.instance.overlayUI("send_money.fxml");
    }

    public void settingsClicked(ActionEvent event) {
        Main.OverlayUI<WalletSettingsController> screen = Main.instance.overlayUI("wallet_settings.fxml");
        screen.controller.initialize(null);
    }

    public void restoreFromSeedAnimation() {
        // Buttons slide out ...
        TranslateTransition leave = new TranslateTransition(Duration.millis(1200), controlsBox);
        leave.setByY(80.0);
        leave.play();
    }

    public void readyToGoAnimation() {
        // Buttons slide in and clickable address appears simultaneously.
        TranslateTransition arrive = new TranslateTransition(Duration.millis(1200), controlsBox);
        arrive.setInterpolator(new ElasticInterpolator(EasingMode.EASE_OUT, 1, 2));
        arrive.setToY(0.0);
        FadeTransition reveal = new FadeTransition(Duration.millis(1200), addressControl);
        reveal.setToValue(1.0);
        ParallelTransition group = new ParallelTransition(arrive, reveal);
        group.setDelay(NotificationBarPane.ANIM_OUT_DURATION);
        group.setCycleCount(1);
        group.play();
    }

    public DownloadProgressTracker progressBarUpdater() {
        return model.getDownloadProgressTracker();
    }

    public void onTimestampClicked(ActionEvent event) {
        // Ask the user for the document to timestamp
        File doc = new FileChooser().showOpenDialog(Main.instance.mainWindow);
        if (doc == null) return; // User cancelled
        try {
            timestamp(doc);
        } catch (IOException e) {
            crashAlert(e);
        } catch (InsufficientMoneyException e) {
            informationalAlert("Insufficient funds",
                    "You need bitcoins in this wallet in order to pay network fees.");
        }
    }

    private void timestamp(File doc) throws IOException, InsufficientMoneyException {
        // Hash it
        Sha256Hash hash = Sha256Hash.hashFileContents(doc);

        // Create a tx with an OP_RETURN output
        Transaction tx = new Transaction(Main.params);
        tx.addOutput(Coin.ZERO, ScriptBuilder.createOpReturnScript(hash.getBytes()));

        // Send it to the Bitcoin network
        Main.bitcoin.wallet().sendCoins(Wallet.SendRequest.forTx(tx));

        // Add it to the UI list
        Proof proof = new Proof();
        proof.tx = tx.bitcoinSerialize();
        proof.filename = doc.toString();
        pendingProofsList.getItems().add(proof);

        // Grab the merkle branch when it appears in the block chain
        Main.bitcoin.peerGroup().addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onBlocksDownloaded(Peer peer, Block block, FilteredBlock filteredBlock, int blocksLeft) {
                List<Sha256Hash> hashes = new ArrayList<>();
                PartialMerkleTree tree = filteredBlock.getPartialMerkleTree();
                tree.getTxnHashAndMerkleRoot(hashes);
                if (hashes.contains(tx.getHash())) {
                    proof.partialMerkleTree = tree.bitcoinSerialize();
                    proof.blockHash = filteredBlock.getHash();
                }
            }
        });

        // Wait for confirmations (3)
        tx.getConfidence().addEventListener((confidence, reason) -> {
            if (confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING)
                return;
            proof.depth.set(confidence.getDepthInBlocks());
            if (proof.depth.get() == 3) {
                // Save the proof to disk
                String filename = doc.toString() + ".timestamp";
                try {
                    proof.saveTo(filename);
                    // Remove it from the UI list
                    pendingProofsList.getItems().remove(proof);
                    // Notify the user that it's done
                    informationalAlert("Proof complete", "Saved to " + filename);
                } catch (IOException e) {
                    crashAlert(e);
                }
            }
        });
    }

    public void onVerifyClicked(ActionEvent event) {
        // Ask the user for the document to verify
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Timestamp proofs", "*.timestamp"));
        File proofFile = chooser.showOpenDialog(Main.instance.mainWindow);
        if (proofFile == null) return;  // User cancelled.

        try {
            StoredBlock cursor = verify(proofFile);
            // Notify the user that the proof is valid and what the timestamp is
            informationalAlert("Proof valid!", "Document existed at " + cursor.getHeader().getTime());
        } catch (IOException | BlockStoreException e) {
            crashAlert(e);
        } catch (ProofException e) {
            informationalAlert("Proof was invalid", e.getMessage());
        }
    }

    private StoredBlock verify(File proofFile) throws IOException, ProofException, BlockStoreException {
        // Load the proof file
        Proof proof = Proof.readFrom(proofFile.getAbsolutePath());

        // Hash the document
        String docFile = proofFile.getAbsoluteFile().toString().replace(".timestamp", "");
        Sha256Hash hash = Sha256Hash.hashFileContents(new File(docFile));

        // Verify the hash is in the OP_RETURN output of the tx
        Transaction tx = new Transaction(Main.params, proof.tx);
        boolean found = false;
        for (TransactionOutput output : tx.getOutputs()) {
            if (!output.getScriptPubKey().isOpReturn()) continue;
            //noinspection ConstantConditions
            if (!Arrays.equals(output.getScriptPubKey().getChunks().get(1).data,
                    hash.getBytes()))
                throw new ProofException("Hash does not match OP_RETURN output");
            found = true;
            break;
        }
        if (!found) throw new ProofException("No OP_RETURN output in transaction");
        // Verify the transaction is in the Merkle proof
        PartialMerkleTree tree = new PartialMerkleTree(Main.params, proof.partialMerkleTree, 0);
        List<Sha256Hash> hashes = new ArrayList<>();
        Sha256Hash merkleRoot = tree.getTxnHashAndMerkleRoot(hashes);
        if (!hashes.contains(tx.getHash()))
            throw new ProofException("Transaction not found in Merkle proof");

        // Find the block given the hash
        StoredBlock cursor = Main.bitcoin.chain().getChainHead();
        while (cursor != null && !cursor.getHeader().getHash().equals(proof.blockHash)) {
            cursor = cursor.getPrev(Main.bitcoin.store());
        }
        if (cursor == null)
            throw new ProofException("Could not find given block hash: " + proof.blockHash);

        // Verify the Merkle proof is linked to the block header
        if (!cursor.getHeader().getMerkleRoot().equals(merkleRoot))
            throw new ProofException("Merkle root does not match block header");
        return cursor;
    }

    private class ProofException extends Exception {
        public ProofException(String s) {
            super(s);
        }
    }
}
