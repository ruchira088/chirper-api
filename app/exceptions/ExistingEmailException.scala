package exceptions

case class ExistingEmailException(email: String) extends ExistingResourceException {
  override def getMessage: String = s"Email address is already registered to an existing user: $email"
}