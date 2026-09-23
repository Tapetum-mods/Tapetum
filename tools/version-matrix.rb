require 'json'
require 'open3'
require_relative 'verify-artifact'

module TapetumVersionMatrix
  def self.targets(path)
    versions = JSON.parse(File.read(path))
    unless versions.is_a?(Array) && versions.length == versions.uniq.length &&
        versions.all? { |version| version.is_a?(String) && version.match?(/\A\d+(?:\.\d+)+\z/) }
      raise ArgumentError, 'Expected unique, exact Minecraft versions'
    end
    versions
  end

  def self.inspect_version(root, version)
    properties, _, status = Open3.capture3('git', '-C', root, 'show', "refs/heads/#{version}:gradle.properties")
    return {version: version, state: 'not_ported'} unless status.success?
    actual = properties.lines.map { |line| line[/\AminecraftVersion=(.+?)\s*\z/, 1] }.compact
    return {version: version, state: 'branch_target_mismatch', actual: actual} unless actual == [version]
    directory = File.join(root, 'build/version-artifacts', version)
    artifacts = Dir[File.join(directory, '*.jar')].reject { |path| path.end_with?('-sources.jar') }
    return {version: version, state: 'source_only'} if artifacts.empty?
    raise ArgumentError, "Duplicate production artifacts for #{version}" unless artifacts.length == 1
    artifact = TapetumArtifact.verify(artifacts.first)
    raise ArgumentError, "Artifact target mismatch for #{version}" unless artifact[:game] == version
    {version: version, state: 'artifact_verified_game_untested', artifact: artifact}
  end
end

if $PROGRAM_NAME == __FILE__
  abort 'Usage: ruby tools/version-matrix.rb [--json]' unless ARGV.empty? || ARGV == ['--json']
  root = File.expand_path('..', __dir__)
  begin
    rows = TapetumVersionMatrix.targets(File.join(root, 'minecraft-versions.json')).map do |version|
      TapetumVersionMatrix.inspect_version(root, version)
    end
    if ARGV.include?('--json')
      puts JSON.pretty_generate(rows)
    else
      puts '| Minecraft | Local source/artifact state |'
      puts '|---|---|'
      rows.each { |row| puts "| #{row[:version]} | #{row[:state]} |" }
      puts "\nArtifact integrity is not proof of a successful build, launch or faithful shader rendering."
    end
  rescue ArgumentError, JSON::ParserError, SystemCallError => error
    abort error.message
  end
end
