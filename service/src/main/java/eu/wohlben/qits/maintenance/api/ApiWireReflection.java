package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import eu.wohlben.qits.maintenance.dto.GroupDto;
import eu.wohlben.qits.maintenance.dto.PinDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.pending.Change;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API.
 *
 * <p>{@code ScanController.scan} and {@code RepositoryController.bump} return {@code
 * Response.entity(...)}, which hides the entity type from the build-time analysis — so in the
 * native binary serialization fails at runtime with a 500 while every JVM test stays green.
 * Measured on a sibling, not theoretical: qits-serviceregistry's first live {@code PUT
 * /services/{name}} answered 500 on exactly this.
 *
 * <p>Some of these types happen to be reachable today through a declared return type; they are all
 * listed anyway, because which ones the analysis finds is an implementation detail no test guards.
 *
 * <p><b>A new response type joins this list in the commit that adds it.</b>
 */
@RegisterForReflection(
    targets = {
      RepositoryController.AcceptedResponse.class,
      ScanController.StartScanRequest.class,
      ScanController.StartScanRequest.Response.class,
      RepositoryDto.class,
      RepositoryDetailDto.class,
      GroupDto.class,
      PinDto.class,
      DependencyDto.class,
      DependencyDto.DependencyPinDto.class,
      BumpDto.class,
      Change.class
    })
final class ApiWireReflection {

  private ApiWireReflection() {}
}
