require 'json'
require 'digest'
require 'open3'

module TapetumArtifact
  def self.verify(path, release_tag: '')
    path = File.realpath(path)
    raise ArgumentError, 'Expected a production JAR, not sources' unless path.end_with?('.jar') && !path.end_with?('-sources.jar')
    _, error, status = Open3.capture3('unzip', '-tq', path)
    raise ArgumentError, "Invalid JAR: #{error}" unless status.success?
    contents, error, status = Open3.capture3('unzip', '-p', path, 'fabric.mod.json')
    raise ArgumentError, "Missing Fabric metadata: #{error}" unless status.success?
    metadata = JSON.parse(contents)
    game = metadata.fetch('depends').fetch('minecraft')
    version = metadata.fetch('version')
    raise ArgumentError, 'Minecraft target must be exact' unless game.is_a?(String) && game.match?(/\A[0-9]+(?:\.[0-9]+)+\z/)
    suffix = /\+mc#{Regexp.escape(game)}(?:-(?:local|build\.[0-9]+))?\z/
    raise ArgumentError, 'Version does not match game target' unless version.is_a?(String) && version.match?(suffix)
    raise ArgumentError, 'Filename does not match metadata' unless File.basename(path) == "tapetum-shaders-#{version}.jar"
    raise ArgumentError, 'Not a standalone Tapetum artifact' unless metadata['id'] == 'tapetumshaders' &&
      metadata['license'] == 'LGPL-3.0-only' &&
      metadata.fetch('depends').keys.sort == %w[fabricloader java minecraft]
    listing, _, status = Open3.capture3('unzip', '-Z1', path)
    raise ArgumentError, 'Cannot list archive' unless status.success?
    entries = listing.lines.map(&:strip)
    %w[dev/tapetum/shaders/TapetumShaders.class META-INF/licenses/tapetum/LICENSE].each do |entry|
      raise ArgumentError, "Missing #{entry}" unless entries.include?(entry)
    end
    raise ArgumentError, 'Unexpected renderer in archive' if entries.any? { |entry| entry.match?(/iris|sodium/i) }
    unless release_tag.empty?
      raise ArgumentError, 'Release tag must exactly match a non-snapshot artifact' unless
        release_tag == "v#{version}" && !version.include?('-snapshot+') && !version.match?(/-(?:local|build\.[0-9]+)\z/)
    end
    {path: path, game: game, version: version, sha256: Digest::SHA256.file(path).hexdigest}
  end
end

if $PROGRAM_NAME == __FILE__
  abort 'Usage: ruby tools/verify-artifact.rb JAR_OR_BUILD_LIBS_DIRECTORY' unless ARGV.length == 1
  begin
    input = ARGV.first
    files = File.directory?(input) ? Dir[File.join(input, 'tapetum-shaders-*.jar')].reject { |p| p.end_with?('-sources.jar') } : [input]
    raise ArgumentError, 'Expected exactly one production JAR; run clean build first' unless files.length == 1
    puts JSON.pretty_generate(TapetumArtifact.verify(files.first, release_tag: ENV.fetch('RELEASE_TAG', '')))
  rescue ArgumentError, KeyError, JSON::ParserError, SystemCallError => error
    abort error.message
  end
end
