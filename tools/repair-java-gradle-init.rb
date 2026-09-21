#!/usr/bin/env ruby
# Restore only the exact JDT LS resource identified by a missing temporary script's hash.
require 'digest'
require 'open3'
require 'tmpdir'

module JavaGradleInitRepair
  def self.restore(archive, missing_path)
    archive = File.realpath(archive)
    unless File.basename(archive).match?(/\Aorg\.eclipse\.jdt\.ls\.core_[\w.-]+\.jar\z/)
      raise ArgumentError, 'Expected the installed Java extension JDT LS core JAR'
    end
    name = File.basename(missing_path)
    unless name.match?(/\A[0-9a-f]{64}\.gradle\z/) &&
           File.realpath(File.dirname(missing_path)) == File.realpath(Dir.tmpdir)
      raise ArgumentError, 'Expected a SHA-256-named .gradle file directly in the current temporary directory'
    end
    destination = File.join(File.realpath(Dir.tmpdir), name)
    if File.exist?(destination) || File.symlink?(destination)
      raise ArgumentError, 'Refusing to overwrite an existing file or symlink'
    end

    entries, status = Open3.capture2('unzip', '-Z1', archive)
    raise ArgumentError, 'Cannot read the JDT LS archive' unless status.success?
    entries.lines.map(&:strip).grep(%r{\Agradle/[\w-]+/[\w.-]+\.gradle\z}).each do |entry|
      source, result = Open3.capture2('unzip', '-p', archive, entry)
      next unless result.success? && Digest::SHA256.hexdigest(source) == name.delete_suffix('.gradle')

      # Exclusive creation also refuses a file or symlink created after the check above.
      File.open(destination, File::WRONLY | File::CREAT | File::EXCL, 0o600) { |file| file.write(source) }
      return entry
    end
    raise ArgumentError, 'No bundled Gradle script matches the requested SHA-256; nothing was written'
  end
end

if $PROGRAM_NAME == __FILE__
  abort 'Usage: ruby tools/repair-java-gradle-init.rb JDT_LS_CORE_JAR MISSING_TEMP_SCRIPT' unless ARGV.length == 2
  begin
    entry = JavaGradleInitRepair.restore(*ARGV)
    puts "Restored #{entry} with verified SHA-256. Refresh the Java project in the editor."
  rescue ArgumentError, SystemCallError => error
    abort error.message
  end
end
