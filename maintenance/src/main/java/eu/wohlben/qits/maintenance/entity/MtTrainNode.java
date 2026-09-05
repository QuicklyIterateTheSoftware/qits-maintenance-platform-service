package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One expected adopter of one release, and the END it is expected to adopt through.
 *
 * <p><b>Keyed by {@code (train, consumer, end kind)} and deliberately not by {@code (train,
 * consumer)}.</b> One repository can owe a train both a manifest bump and a config pin, and closing
 * one must not close the other.
 *
 * <p><b>Everything on it is what was true AT SPAWN.</b> The archetype is copied rather than read
 * through {@code mt_repository} at display time, because the train is a log: a repository
 * re-classified next month does not retroactively change the kind of adoption this node was placed
 * for.
 */
@Entity
@Table(name = "mt_train_node")
public class MtTrainNode extends PanacheEntityBase {

  @Id public UUID id;

  /**
   * The train this node belongs to. The one real foreign key on these two tables — both ends are
   * this context's own, and a node has no meaning apart from its train.
   */
  @Column(name = "train_id", nullable = false)
  public UUID trainId;

  /**
   * Who is expected to adopt: a repository NAME for {@code LINKED} and {@code DAEMON_PIN}, and the
   * APPLICATION name for {@code CONFIG_IMAGE_PIN}. Two namespaces that mostly agree — the
   * {@code qits-ci} application is built from {@code qits-ci-service} — and the column holds
   * whichever one the end kind beside it addresses.
   */
  @Column(nullable = false, length = 255)
  public String consumer;

  /**
   * What the consumer was, as the catalog classified it at spawn. The raw string, nullable and
   * unvalidated, exactly like the {@code mt_repository} column it is copied from: a word this build
   * does not know costs a node its typed reading and nothing else.
   */
  @Column(columnDefinition = "text")
  public String archetype;

  /** {@code TrainNodeState}'s names: PENDING, ADOPTED or LANDED. Every node is born PENDING. */
  @Column(nullable = false, length = 32)
  public String state;

  /** {@code TrainEndKind}'s names: LINKED, CONFIG_IMAGE_PIN or DAEMON_PIN. */
  @Column(name = "end_kind", nullable = false, length = 32)
  public String endKind;

  /**
   * <b>THE CONSUMER'S OWN RELEASE that carries the adoption</b> — not the version of the dependency
   * it took. Null while PENDING.
   *
   * <p>It is read as one half of an address: {@code
   * release-requests/by-release/<consumer catalog id>/<adopted_version>} resolves the release
   * request the consumer opened for that release, and that resolver matches the CONSUMER's own
   * releases by version. The dependency version would resolve to nothing there.
   *
   * <p>Beside {@link #childTrainId} rather than derivable from it: the link is null until the
   * sibling consumer spawns the adopting release's station, and the address above has to compose in
   * that window too.
   *
   * <p><b>THE TWO POLLED ENDS ARE THE EXCEPTION, and they have to be.</b> For {@code
   * CONFIG_IMAGE_PIN} and {@code DAEMON_PIN} this column holds the version that was OBSERVED —
   * what the deployment configuration pins, what qits-ci hands out — because there is no consumer
   * release for it to hold instead: an application does not release, and a service handing out a
   * daemon build did not publish anything by doing so. So no address composes from these rows, and
   * a reader must branch on {@link #endKind} before treating this as one. See {@code
   * train/TrainSweep}.
   */
  @Column(name = "adopted_version", length = 255)
  public String adoptedVersion;

  @Column(name = "adopted_at")
  public Instant adoptedAt;

  /**
   * The adoption's own train, when adopting produced a release of its own. This is the link that
   * makes the estate's journey a graph rather than a heap of unrelated rows. Flat, no foreign key.
   */
  @Column(name = "child_train_id")
  public UUID childTrainId;

  @Column(name = "landed_at")
  public Instant landedAt;
}
