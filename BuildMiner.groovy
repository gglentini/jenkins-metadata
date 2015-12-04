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
  def causes
  def node
  def parameters
  def changes
  def downstreams
  def upstreams
  //def blocking
  //def nonblocking
  def subprojects
  def artifacts
  //def s3artifacts
  def testresult

  /**
   * Make a custom representation of a Jenkins build,
   * out of the original one received as parameter
   */
  def CCIBuild(build, listener) {
  	name = build.getProject().getFullName()
    number = build.getNumber()

    result = new DetailedResult(build.getResult().toString(),
                                build.getActions(
                                  com.sonyericsson.jenkins.plugins.bfa.model.FailureCauseBuildAction.class))

    description = build.getDescription()
    duration = build.getDuration()
    timestamp = build.getTimestamp()

    // NOTE: the time spent in queue requires the Metrics plugin to be installed
    queuing = getQueueDuration()

    causes = getCauses(build.getCauses())

    // [LL] - TODO: we could create a class Node to keep more details about a node
    node = build.getBuiltOnStr()

    parameters = build.getBuildVariables()

    changes = getChangeSetsDetails(build.getChangeSets(), build.getBuildVariables(),
                                    build.getEnvironment(listener))

    downstreams = build.getDownstreamBuilds()
    upstreams = build.getUpstreamBuilds()

    subprojects = getSubProjects(build.getAllActions())

    artifacts = geAlltArtifacts()

    testresult = getTestResult(build.getAllActions())

  }

  def getQueueDuration() {
    try {
      return buildToAnalyze.getAction(
              jenkins.metrics.impl.TimeInQueueAction.class).getQueuingDurationMillis()
    }
    catch {
      // [LL] - TODO: add some logging
      return null
    }
  }
  // [LL] - TODO: we could create a class Cause to keep more details about a cause,
  // not only the short description
  def getCauses(causes) {}

  // [LL] - TODO: we could create a class Change to keep more details about a change
  def getChangeSetsDetails(changes, parameters, env) {}

  def getSubProjects(actions) {}

  def getAllArtifacts() {}

  def getTestResult() {}
}

/**
 * Extended representation of build result,
 * adding information taken from the Build Failure Analyzer plugin
 */
class DetailedResult {
  def status
  def causes = []

  // def DetailedResult(result, failureName, failureDescription, failureCategories) {
  //   status = result
  //   causes = [
  //             ['name': failureName, 'description': failureDescription,
  //               'categories': failureCategories]
  //            ]
  //   //cause['name'] = failureName
  //   //cause['description'] = failureDescription
  //   //cause['categories'] = failureCategories
  // }

  def DetailedResult(result, bfaActions) {
    status = result
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


// [LL] - TODO: smartly generate the jobs we are instered in, hard-coded in the 1st iteration
//def jobsToAnalyze = ['sdk-build-ios-internal-release-all_archs', 'MOS_dal-pre-commit-verify-trigger',]
def jobsToAnalyze = ['team-routing/server/test-acceptance-hlp.xml-only-traffic']

def cciBuilds = []

jobsToAnalyze.each {
  	def jenkinsJob = Jenkins.instance.getItemByFullName(it)
  	//def jenkinsBuild = jenkinsJob.getLastBuild()
    def jenkinsBuild = jenkinsJob.getBuildByNumber(14463)


  	// [LL] - TODO: an object for each job here, think about memory optimization
  	def cciBuild = new BuildLightEncoder()
    cciBuild.name = jenkinsBuild.getProject().getFullName()
  	cciBuild.number = jenkinsBuild.getNumber()
    cciBuild.result = new DetailedResult(jenkinsJob.getResult().toString(),
                                          jenkinsBuild.getActions(
      com.sonyericsson.jenkins.plugins.bfa.model.FailureCauseBuildAction.class))

  	cciBuilds.add(cciBuild.encode(pretty=true))
}

println cciBuilds
