package dev.multiloader.api.locating;

/**
 * {@link IModFileCandidateLocator} 的输出端.
*/
@FunctionalInterface
public interface IModFileCandidateConsumer {

    void accept(ModFileCandidate candidate);
}
