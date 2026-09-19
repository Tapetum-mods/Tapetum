require 'json'
require 'fileutils'
require 'timeout'

# Uses cached, public game artifacts only. No launcher account file or authentication token is read.
version = ARGV.fetch(0)
targets = {'26.1.2' => ['mc26.1', '26.1.2-0.19.5', 'Tapetum Shaders (1)'],
           '26.2' => ['mc26.2', '26.2-0.19.3', 'Tapetum Sahders 26.2']}
mod, launcher_id, profile_name = targets.fetch(version)
root = File.expand_path('..', __dir__)
modrinth = File.expand_path('~/Library/Application Support/ModrinthApp')
meta = File.join(modrinth, 'meta')
info = JSON.parse(File.read(File.join(meta, 'versions', launcher_id, "#{launcher_id}.json")))
run = File.join(root, 'run', "smoke-#{version}-#{Time.now.strftime('%Y%m%d-%H%M%S')}")
abort('Refusing to reuse a QA profile') if File.exist?(run)
%w[mods config shaderpacks natives].each { |d| FileUtils.mkdir_p(File.join(run, d)) }
puts "QA profile: #{run}"
$stdout.flush

artifacts = [File.join(root, mod, 'build/libs', "tapetum-shaders-0.1.0+mc#{version}.jar"),
             File.join(root, mod, 'build/libs', "tapetum-smoke-test-0.1.0+mc#{version}.jar")]
%w[sodium fabric-api].each do |name|
  files = Dir[File.join(modrinth, 'profiles', profile_name, 'mods', "#{name}*.jar")]
  abort("Expected exactly one installed #{name}") unless files.length == 1
  artifacts.concat(files)
end
artifacts.each { |p| FileUtils.cp(p, File.join(run, 'mods')) }
packs = Dir[File.join(modrinth, 'profiles', 'Tapetum Shaders (1)', 'shaderpacks', '*.zip')]
packs.each { |p| FileUtils.cp(p, File.join(run, 'shaderpacks')) }
File.write(File.join(run, 'config/tapetumshaders.properties'), "shadersEnabled=false\n")
File.write(File.join(run, 'config/iris.properties'), "enableShaders=false\ndisableUpdateMessage=true\n")
File.write(File.join(run, 'options.txt'), "onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:5\nmaxFps:60\nguiScale:2\nlang:en_us\n")

libraries = info.fetch('libraries').map do |lib|
  rules = lib.fetch('rules', [])
  allowed = rules.empty?
  rules.each do |r|
    os = r.fetch('os', {})
    matches = (!os['name'] || %w[osx osx-arm64].include?(os['name'])) &&
              (!os['arch'] || %w[aarch64 arm64].include?(os['arch']))
    allowed = r['action'] == 'allow' if matches
  end
  next unless allowed && lib.fetch('include_in_classpath', true)
  path = lib.dig('downloads', 'artifact', 'path')
  unless path
    group, name, ver, classifier = lib.fetch('name').split(':')
    path = "#{group.tr('.', '/')}/#{name}/#{ver}/#{name}-#{ver}#{classifier ? '-' + classifier : ''}.jar"
  end
  next if path.end_with?('-natives-macos.jar') # Intel binaries are not used on this Apple Silicon host.
  absolute = File.join(meta, 'libraries', path)
  abort("Missing cached library: #{absolute}") unless File.file?(absolute)
  absolute
end.compact
libraries << File.join(meta, 'versions', launcher_id, "#{launcher_id}.jar")
java = '/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java'
args = [java, '-XstartOnFirstThread', '-Xmx3G', '--enable-native-access=ALL-UNNAMED',
        '--sun-misc-unsafe-memory-access=allow', '-Dtapetum.isolatedSmokeTest=true',
        "-Djava.library.path=#{run}/natives", "-Dorg.lwjgl.system.SharedLibraryExtractPath=#{run}/natives",
        '-cp', libraries.join(File::PATH_SEPARATOR), info.fetch('mainClass'),
        '--username', 'TapetumQA', '--uuid', '11111111111111111111111111111111', '--accessToken', '0',
        '--version', version, '--gameDir', run, '--assetsDir', File.join(meta, 'assets'),
        '--assetIndex', info.fetch('assetIndex').fetch('id'), '--userType', 'msa', '--versionType', 'release',
        '--width', '960', '--height', '600']
log = File.join(run, 'launcher.log')
pid = Process.spawn(*args, chdir: run, out: log, err: [:child, :out])
puts "QA PID: #{pid}; log: #{log}"
$stdout.flush
begin
  Timeout.timeout(900) { Process.wait(pid) }
rescue Timeout::Error
  Process.kill('TERM', pid)
  Process.wait(pid)
  abort('QA timed out; only the test process was stopped')
end
failure = File.join(run, 'smoke-failure.txt')
abort(File.read(failure)) if File.exist?(failure)
success = File.join(run, 'smoke-success.txt')
abort("No success report; inspect #{log}") unless File.file?(success)
puts File.read(success)
