package com.techducat.speso;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persistence, as dumb as it gets: one text line per block, appended as blocks arrive.
 * On startup the Ledger replays and fully re-validates every line, so a corrupted or
 * tampered file can never put bad state in memory; the bad tail is simply cut off.
 * (Genesis is hard-coded, so block 0 is not stored.)
 */
final class Store {
    private final Path chainFile, peersFile;

    Store(String dir) {
        try {
            Path d = Paths.get(dir);
            Files.createDirectories(d);
            chainFile = d.resolve("chain.dat");
            peersFile = d.resolve("peers.dat");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    Path dir() { return chainFile.getParent(); }

    List<String> readBlocks() {
        try {
            return Files.exists(chainFile) ? Files.readAllLines(chainFile, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    void append(Block b) {
        try {
            Files.writeString(chainFile, b.encode() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Replace the file with the given chain (used after a reorg or to cut a corrupt tail). */
    void rewrite(List<Block> chain) {
        try {
            Path tmp = chainFile.resolveSibling("chain.dat.tmp");
            List<String> lines = new ArrayList<>();
            for (int i = 1; i < chain.size(); i++) lines.add(chain.get(i).encode());
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, chainFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    List<String> readPeers() {
        try {
            return Files.exists(peersFile) ? Files.readAllLines(peersFile) : List.of();
        } catch (IOException e) { return List.of(); }
    }

    void writePeers(Collection<String> peers) {
        try { Files.write(peersFile, new ArrayList<>(peers)); } catch (IOException ignored) {}
    }
}
