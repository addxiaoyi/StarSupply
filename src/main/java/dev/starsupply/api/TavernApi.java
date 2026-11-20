package dev.starsupply.api;

import dev.starsupply.api.TavernModels.Board;
import dev.starsupply.api.TavernModels.Hub;
import dev.starsupply.api.TavernModels.ScoutReport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * Optional StarSupply Bukkit service for merchant hubs and physical route reconnaissance.
 * Call on the actor's entity scheduler. The bridge captures location/permissions and fires
 * the same cancellable SupplyOperationEvent as commands before dispatching to the SQL worker.
 * Reports contain route observations only; the API never exposes enemy stocks or balances.
 */
public interface TavernApi {
    CompletionStage<Hub> registerHub(Player actor, String id);
    CompletionStage<Void> removeHub(Player actor, String id);
    CompletionStage<ScoutReport> scoutRoute(Player actor, String roadId);
    CompletionStage<ScoutReport> publishIntelligence(Player actor, UUID reportId, String hubId);
    CompletionStage<Board> tavernBoard(Player actor, String hubId);
    CompletionStage<List<Hub>> tavernHubs(Player actor);
}
