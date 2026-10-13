package dev.compileloop.repair;

/**
 * Source of repair proposals. Deliberately tiny: it receives a prepared,
 * read-only context and returns a proposal. It gets no access to the project
 * filesystem, a shell, or anything else it could use to act on its own.
 * Whatever it returns is untrusted data until the PatchValidator accepts it.
 */
public interface AIProvider {
    PatchProposal proposeRepair(RepairContext context) throws AIProviderException;
}
