package dev.skillmanager.effects;

import java.util.List;

@FunctionalInterface
public interface ResultDecoder<R> {
    R decode(List<EffectReceipt> receipts);
}
