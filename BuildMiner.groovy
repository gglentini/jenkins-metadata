import jenkins.model.Jenkins;

import groovy.json.JsonOutput;
import groovy.json.JsonBuilder;


/**
 * Representation of a build in the CCI system
 */
class CCIBuild {
  def name
  def number
  def result
  def description
  def duration
  def timestamp
  def queuing
  def causes = []
  def node
  def parameters
  def changes
  def downstreams
  def upstreams
  def subprojects = []
  def artifacts = []
  def testresult

  /**
   * Make a custom representation of a Jenkins build,
   * out of the original one received as parameter
   */
  def CCIBuild(build, listener) {
    name = build.getProject().getFullName()
    number = build.getNumber()

    result = new DetailedResult(build)

    description = build.getDescription()
    duration = build.getDuration()
    timestamp = build.getTimestamp()

    // NOTE: the time spent in queue requires the Metrics plugin to be installed
    // otherwise it will always be null
    queuing = getQueueDuration(build)

    causes = getCauses(build.getCauses())

    // [LL] - TODO: we could create a class Node to keep more details about a node
    node = build.getBuiltOnStr()

    parameters = build.getBuildVariables()

    changes = getChangeSetsDetails(build.getChangeSets(), build.getBuildVariables(),
                                    build.getEnvironment(listener))

    downstreams = build.getDownstreamBuilds()
    upstreams = build.getUpstreamBuilds()

     // NOTE: the time spent in queue requires the Parameterized trigger plugin to be installed
    // otherwise it will always be an empty list
    subprojects = getSubProjects(build)

    artifacts = getAllArtifacts(build)

    testresult = getTestResult(build)

  }

  def getQueueDuration(build) {
    try {
      return build.getAction(
              jenkins.metrics.impl.TimeInQueueAction.class).getQueuingDurationMillis()
    }
    catch (Exception e) {
      // [LL] - TODO: add some logging
      return null
    }
  }

  // [LL] - TODO: we could create a class Cause to keep more details about a cause,
  // not only the short description
  // We can't simply encode each cause, because some of them (e.g. upstream trigger) is not a simple object
  // but it has nested objects (e.g. build, run) inside it
  def getCauses(causes) {
    def to_return = []
    causes.each { cause ->
      to_return.add(cause.getShortDescription())
    }
    return to_return
  }

  // [LL] - TODO: we could create a class Change to keep more details about a change
  // [LL] - TODO: the implementation fits ONLY Gerrit-triggered builds currently
  def getChangeSetsDetails(changes, parameters, env) {
    def to_return = []
    def tmp = [:]
    if (parameters.get('GERRIT_CHANGE_ID')) {
      tmp['id'] = parameters.get('GERRIT_CHANGE_ID')
      tmp['revision'] = parameters.get('GERRIT_PATCHSET_REVISION')
      tmp['repository'] = parameters.get('GERRIT_PROJECT')
      tmp['kind'] = parameters.get('GERRIT_EVENT_TYPE')
      to_return.add(tmp)
    }
    return to_return
  }

  def getSubProjects(build) {
    def to_return = [ 'blocking': [], 'nonblocking': [] ]
    def triggers

    // get all parametrized trigger plugin actions
    try {
      triggers = build.getActions(hudson.plugins.parameterizedtrigger.BuildInfoExporterAction)
    }
    catch (Exception e) {
      return to_return
    }

    // if any
    // for each, get the blocking builds
    triggers.each { action ->
        // for each blocking build, get name and number
        action.getTriggeredBuilds().each {
          if (it) // need to add this check, as children job can be deleted 
            to_return['blocking'].add(['name': it.getProject().getFullName(), 'number': it.getNumber()])
        }
        action.getTriggeredProjects().each {
          // TODO: it seems the number is not available in the plugin internals for non-blocking builds
          //tmp['number'] = it.getNumber()
          if (it)
            to_return['nonblocking'].add(['name': it.getFullName()])
        }
    }
    return to_return
  }

  def getAllArtifacts(build) {
    def to_return = [ 'master': [], 's3': [] ]
    def s3Action

    build.getArtifacts().each {
      to_return['master'].add(it.toString())
    }

    try {
      s3Action = build.getAction(hudson.plugins.s3.S3ArtifactsAction.class)
    }
    catch (Exception e ) {
      return to_return
    }
    if (s3Action) {
      s3Action.getArtifacts().each {
      to_return['s3'].add(it.getName())
      }
    }
    return to_return
  }

  def getTestResult(build) {
    def to_return = []
    def testPublishers
    // get all test actions
    try {
      testPublishers = build.getActions(
        hudson.tasks.test.AbstractTestResultAction.class)
    }
    catch (Exception e) {
      return to_return
    }
      testPublishers.each {
        def tmp = [:]
        // [LL] - TODO: retrieving AbstractTestResultAction info only,
        // as each TestResult could have different implementations
        //tmp['framework'] = it.getResult().getName()
        tmp['name'] = it.getDisplayName()
        tmp['total'] = it.getTotalCount()
        tmp['fail'] = it.getFailCount()
        tmp['skip'] = it.getSkipCount()

        to_return.add(tmp)
      }
    return to_return
  }

 /**
  * Encodes the object, returning the JSON representation (as a String object)
  * By default, the output is not pretty-printed
  * For a pretty-printed human-readable output (for debugging), set the 'pretty' parameter to true
  */
  def encode(pretty=false) {
    def jsonBuilder = new JsonBuilder(this)
    def encoded = pretty ? jsonBuilder.toPrettyString() : jsonBuilder.toString()
    return encoded
  }
}

/**
 * Extended representation of build result,
 * adding information taken from the Build Failure Analyzer plugin
 */
class DetailedResult {
  def status
  def causes = []


  def DetailedResult(build) {
      this(build.getResult().toString(), build.getAllActions())
  }

  def DetailedResult(result, actions) {
    status = result
    def bfaActions
    try {
      bfaActions = actions.findAll {
        it.class == com.sonyericsson.jenkins.plugins.bfa.model.FailureCauseBuildAction.class
      }
    }
    catch (Exception e) {
      return
    }

    bfaActions.each {
      it.getFoundFailureCauses().each {
        causes.add(['name': it.getName(),
                    'description': it.getDescription(),
                    'categories': it.getCategories()])
      }
    }
  }
}

/**
 * JSON encoder (light version, with less fields) of a CCIBuild object
 * Currently, it should be used to build essential reports around builds.
 */
class BuildLightEncoder {
    def name
    def number
    def result

  def BuildLightEncoder() {
    name = null
    number = null
    result = null
  }

  def BuildLightEncoder(name, number, result) {
    this.name = name
    this.number = number
    this.result = result
  }

  /**
   * Encodes the object, returning the JSON representation (as a String object)
   * By default, the output is not pretty-printed
   * For a pretty-printed human-readable output (for debugging), set the 'pretty' parameter to true
  */
  def encode(pretty=false) {
    def jsonBuilder = new JsonBuilder(this)
    def encoded = pretty ? jsonBuilder.toPrettyString() : jsonBuilder.toString()
    return encoded
  }
}
