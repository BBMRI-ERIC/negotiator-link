# Idempotently create the negotiator-link bot user and its API token with a
# FIXED value from ZAMMAD_API_TOKEN in .env.dev
#
# Required env:
#   BOT_API_TOKEN   the fixed token value (== ZAMMAD_API_TOKEN in .env.dev)
# Optional env:
#   BOT_EMAIL       default negotiator-link@local.dev
#   BOT_FIRSTNAME   default "Negotiator Service Account"

token_value = ENV['BOT_API_TOKEN'].to_s
if token_value.empty?
  warn 'BOT_API_TOKEN is required'
  exit 1
end

email     = ENV.fetch('BOT_EMAIL', 'negotiator-link@local.dev')
firstname = ENV.fetch('BOT_FIRSTNAME', 'Negotiator Service Account')

UserInfo.current_user_id = 1

agent_role = Role.find_by(name: 'Agent')

user = User.find_by(email: email)
if user.nil?
  user = User.create!(
    firstname: firstname,
    lastname:  '',
    email:     email,
    login:     email,
    active:    true,
    roles:     [agent_role].compact,
  )
  puts "Created bot user #{email} (id=#{user.id})"
else
  puts "Bot user #{email} already exists (id=#{user.id})"
end

existing = Token.where(action: 'api', user_id: user.id, name: 'negotiator-link').first
if existing&.token == token_value
  puts 'Bot API token already matches .env.dev — nothing to do.'
else
  existing&.destroy!
  token = Token.create!(
    action:     'api',
    name:       'negotiator-link',
    user_id:    user.id,
    persistent: true,
    preferences: { permission: ['ticket.agent'] },
  )
  token.update_column(:token, token_value)
  puts 'Set bot API token to the fixed .env.dev value.'
end

